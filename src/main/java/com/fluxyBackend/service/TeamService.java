package com.fluxyBackend.service;

import com.fluxyBackend.entity.*;
import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.exception.NotFoundException;
import com.fluxyBackend.repository.CompanyRepository;
import com.fluxyBackend.repository.MembershipRepository;
import com.fluxyBackend.repository.TeamInvitationRepository;
import com.fluxyBackend.repository.UserRepository;
import com.fluxyBackend.security.JwtService;
import com.fluxyBackend.security.access.AccessService;
import com.fluxyBackend.security.access.Member;
import com.fluxyBackend.security.access.MemberRole;
import com.fluxyBackend.security.access.Permission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TeamService {

    private static final Duration INVITATION_TTL = Duration.ofDays(7);
    private static final Set<MemberRole> ASSIGNABLE = EnumSet.of(MemberRole.ADMIN, MemberRole.SELLER, MemberRole.VIEWER);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MembershipRepository membershipRepository;
    private final TeamInvitationRepository invitationRepository;
    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final EmailService emailService;

    @Value("${app.frontend_url:http://localhost:5173}")
    private String frontendUrl;

    // ─── Vistas ───────────────────────────────────────────────────────────────

    public record MemberView(Long userId, String fullName, String email, String role, String status,
                             List<String> permissions, boolean customPermissions, Instant joinedAt, boolean you) {}

    public record InvitationView(Long id, String email, String role, List<String> permissions, String status,
                                 Instant expiresAt, Instant createdAt) {}

    public record Team(List<MemberView> members, List<InvitationView> invitations,
                       Map<String, List<String>> roleDefaults, List<String> allPermissions) {}

    public record InviteRequest(String email, String role, List<String> permissions) {}

    public record InviteResult(InvitationView invitation, String acceptUrl, boolean emailSent) {}

    public record UpdateMemberRequest(String role, List<String> permissions, Boolean useRoleDefaults, String status) {}

    public record InvitationInfo(String companyName, String email, String role, boolean valid, String reason) {}

    public record AcceptRequest(String fullName, String password) {}

    public record AcceptResult(String token) {}

    // ─── Consultas ────────────────────────────────────────────────────────────

    public Team team(Member actor) {
        Long companyId = actor.companyId();
        List<Membership> memberships = membershipRepository.findByCompanyId(companyId);
        Map<Long, User> users = userRepository.findAllById(memberships.stream().map(Membership::getUserId).toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));

        List<MemberView> members = memberships.stream()
                .filter(m -> users.containsKey(m.getUserId()))
                .map(m -> memberView(m, users.get(m.getUserId()), actor))
                .sorted(Comparator.comparing((MemberView m) -> MemberRole.valueOf(m.role()).ordinal())
                        .thenComparing(MemberView::fullName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();

        List<InvitationView> invitations = invitationRepository.findByCompanyIdOrderByCreatedAtDesc(companyId).stream()
                .filter(i -> i.getAcceptedAt() == null && i.getRevokedAt() == null)
                .map(TeamService::invitationView)
                .toList();

        Map<String, List<String>> defaults = new LinkedHashMap<>();
        for (MemberRole role : MemberRole.values()) {
            defaults.put(role.name(), List.copyOf(Permission.names(AccessService.effectivePermissions(role, null))));
        }
        return new Team(members, invitations, defaults,
                Arrays.stream(Permission.values()).map(Enum::name).toList());
    }

    // ─── Invitaciones ─────────────────────────────────────────────────────────

    @Transactional
    public InviteResult invite(Member actor, InviteRequest request) {
        String email = normalizeEmail(request.email());
        MemberRole role = assignableRole(request.role());
        ensureCanAssign(actor, role);
        Set<Permission> permissions = request.permissions() == null ? null : grantable(actor, request.permissions());

        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw BusinessException.conflict("EMAIL_HAS_ACCOUNT",
                    "Ese correo ya tiene una cuenta en Fluxy. Por ahora cada cuenta pertenece a un solo negocio: invitá otro correo.");
        }
        // Reenviar reemplaza la invitación anterior: el enlace viejo deja de servir.
        invitationRepository.findByCompanyIdAndEmailIgnoreCase(actor.companyId(), email).stream()
                .filter(i -> i.getAcceptedAt() == null && i.getRevokedAt() == null)
                .forEach(i -> i.setRevokedAt(Instant.now()));

        String token = newToken();
        TeamInvitation invitation = new TeamInvitation();
        invitation.setCompanyId(actor.companyId());
        invitation.setEmail(email);
        invitation.setRole(role.name());
        invitation.setPermissions(permissions == null ? null : Permission.format(permissions));
        invitation.setTokenHash(sha256(token));
        invitation.setExpiresAt(Instant.now().plus(INVITATION_TTL));
        invitation.setInvitedBy(actor.user().getId());
        invitationRepository.save(invitation);

        String acceptUrl = frontendUrl.replaceAll("/$", "") + "/invite/" + token;
        boolean emailSent = emailService.sendTeamInvitationEmail(email, actor.company().getName(),
                actor.displayName(), roleLabel(role), acceptUrl);
        return new InviteResult(invitationView(invitation), acceptUrl, emailSent);
    }

    @Transactional
    public void revokeInvitation(Member actor, Long invitationId) {
        TeamInvitation invitation = invitationRepository.findByIdAndCompanyId(invitationId, actor.companyId())
                .orElseThrow(() -> new NotFoundException("Invitación no encontrada"));
        if (invitation.getAcceptedAt() != null) throw new BusinessException("La invitación ya fue aceptada.");
        invitation.setRevokedAt(Instant.now());
    }

    public InvitationInfo invitationInfo(String token) {
        Optional<TeamInvitation> found = invitationRepository.findByTokenHash(sha256(token));
        if (found.isEmpty()) return new InvitationInfo(null, null, null, false, "NOT_FOUND");
        TeamInvitation invitation = found.get();
        String company = companyRepository.findById(invitation.getCompanyId()).map(Company::getName).orElse(null);
        String reason = invitation.getAcceptedAt() != null ? "ACCEPTED"
                : invitation.getRevokedAt() != null ? "REVOKED"
                : Instant.now().isAfter(invitation.getExpiresAt()) ? "EXPIRED" : null;
        return new InvitationInfo(company, invitation.getEmail(), roleLabel(MemberRole.parse(invitation.getRole())),
                reason == null, reason);
    }

    @Transactional
    public AcceptResult accept(String token, AcceptRequest request) {
        TeamInvitation invitation = invitationRepository.findByTokenHash(sha256(token))
                .filter(TeamInvitation::isPending)
                .orElseThrow(() -> new BusinessException(HttpStatus.GONE, "INVITATION_INVALID",
                        "La invitación no es válida o ya venció. Pedile a quien te invitó que la reenvíe."));
        String fullName = request.fullName() == null ? "" : request.fullName().strip().replaceAll("\\s+", " ");
        if (fullName.length() < 2 || fullName.length() > 150) throw new BusinessException("Ingresá tu nombre completo.");
        String password = request.password() == null ? "" : request.password();
        if (password.length() < 8 || password.length() > 72) {
            throw new BusinessException("La contraseña tiene que tener entre 8 y 72 caracteres.");
        }
        if (userRepository.existsByEmailIgnoreCase(invitation.getEmail())) {
            throw BusinessException.conflict("EMAIL_HAS_ACCOUNT", "Ese correo ya tiene una cuenta. Iniciá sesión.");
        }
        Company company = companyRepository.findById(invitation.getCompanyId())
                .orElseThrow(() -> new NotFoundException("La empresa ya no existe."));

        User user = userRepository.saveAndFlush(User.builder()
                .fullName(fullName)
                .firstName(fullName.split(" ", 2)[0])
                .email(invitation.getEmail())
                .password(passwordEncoder.encode(password))
                .role(Role.TEAM_MEMBER)
                .company(company)
                .build());
        Membership membership = new Membership(user.getId(), company.getId(), invitation.getRole());
        membership.setPermissions(invitation.getPermissions());
        membership.setInvitedBy(invitation.getInvitedBy());
        membershipRepository.save(membership);
        invitation.setAcceptedAt(Instant.now());
        return new AcceptResult(jwtService.generateToken(user.getEmail()));
    }

    // ─── Miembros ─────────────────────────────────────────────────────────────

    @Transactional
    public MemberView updateMember(Member actor, Long userId, UpdateMemberRequest request) {
        if (actor.user().getId().equals(userId)) {
            throw new BusinessException("No podés cambiar tu propio rol ni tu acceso.");
        }
        Membership membership = membershipRepository.findByUserIdAndCompanyId(userId, actor.companyId())
                .orElseThrow(() -> new NotFoundException("Esa persona no es parte de tu equipo."));
        MemberRole current = MemberRole.parse(membership.getRole());
        if (current == MemberRole.OWNER) throw new BusinessException("No se puede modificar al dueño del negocio.");
        ensureCanAssign(actor, current);

        if (request.role() != null) {
            MemberRole role = assignableRole(request.role());
            ensureCanAssign(actor, role);
            membership.setRole(role.name());
        }
        if (Boolean.TRUE.equals(request.useRoleDefaults())) {
            membership.setPermissions(null);
        } else if (request.permissions() != null) {
            membership.setPermissions(Permission.format(grantable(actor, request.permissions())));
        }
        if (request.status() != null) {
            String status = request.status().strip().toUpperCase(Locale.ROOT);
            if (!Membership.STATUS_ACTIVE.equals(status) && !Membership.STATUS_DISABLED.equals(status)) {
                throw new BusinessException("El estado tiene que ser ACTIVE o DISABLED.");
            }
            membership.setStatus(status);
        }
        membership.setUpdatedAt(Instant.now());
        membershipRepository.save(membership);
        User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("Usuario no encontrado"));
        return memberView(membership, user, actor);
    }

    // ─── Apoyo ────────────────────────────────────────────────────────────────

    /** Solo el dueño asigna o modifica administradores. */
    private static void ensureCanAssign(Member actor, MemberRole role) {
        if (role == MemberRole.ADMIN && !actor.isOwner()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "OWNER_ONLY", "Solo el dueño puede gestionar administradores.");
        }
    }

    /** Nadie concede lo que no tiene, ni permisos exclusivos del dueño. */
    private static Set<Permission> grantable(Member actor, List<String> requested) {
        Set<Permission> permissions = Permission.parse(String.join(",", requested));
        permissions.removeAll(Permission.OWNER_ONLY);
        if (!actor.isOwner()) {
            permissions.remove(Permission.TEAM_MANAGE);
            for (Permission p : permissions) {
                if (!actor.can(p)) {
                    throw new BusinessException(HttpStatus.FORBIDDEN, "CANNOT_GRANT",
                            "No podés conceder un permiso que no tenés: " + p.name() + ".");
                }
            }
        }
        return permissions;
    }

    private static MemberRole assignableRole(String value) {
        MemberRole role;
        try {
            role = MemberRole.valueOf(value == null ? "" : value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Rol inválido. Usá ADMIN, SELLER o VIEWER.");
        }
        if (!ASSIGNABLE.contains(role)) throw new BusinessException("Solo puede haber un dueño por negocio.");
        return role;
    }

    private static MemberView memberView(Membership m, User user, Member actor) {
        MemberRole role = MemberRole.parse(m.getRole());
        return new MemberView(user.getId(), user.getFullName(), user.getEmail(), role.name(),
                m.isActive() ? Membership.STATUS_ACTIVE : Membership.STATUS_DISABLED,
                List.copyOf(Permission.names(AccessService.effectivePermissions(role, m.getPermissions()))),
                role != MemberRole.OWNER && m.getPermissions() != null, m.getJoinedAt(),
                user.getId().equals(actor.user().getId()));
    }

    private static InvitationView invitationView(TeamInvitation i) {
        MemberRole role = MemberRole.parse(i.getRole());
        String status = Instant.now().isAfter(i.getExpiresAt()) ? "EXPIRED" : "PENDING";
        return new InvitationView(i.getId(), i.getEmail(), role.name(),
                List.copyOf(Permission.names(AccessService.effectivePermissions(role, i.getPermissions()))),
                status, i.getExpiresAt(), i.getCreatedAt());
    }

    static String roleLabel(MemberRole role) {
        return switch (role) {
            case OWNER -> "Dueño";
            case ADMIN -> "Administrador";
            case SELLER -> "Vendedor";
            case VIEWER -> "Solo lectura";
        };
    }

    private static String normalizeEmail(String email) {
        String value = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        if (!value.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") || value.length() > 150) {
            throw new BusinessException("Ingresá un correo válido.");
        }
        return value;
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                    (value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
