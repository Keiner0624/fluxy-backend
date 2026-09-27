package com.fluxyBackend.service;

import com.fluxyBackend.controller.AuthResponse;
import com.fluxyBackend.entity.*;
import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.exception.ForbiddenException;
import com.fluxyBackend.exception.NotFoundException;
import com.fluxyBackend.repository.CompanyRepository;
import com.fluxyBackend.repository.MembershipRepository;
import com.fluxyBackend.repository.OwnershipTransferRepository;
import com.fluxyBackend.repository.TeamInvitationRepository;
import com.fluxyBackend.repository.UserRepository;
import com.fluxyBackend.security.Hashing;
import com.fluxyBackend.security.PasswordPolicy;
import com.fluxyBackend.security.SessionService;
import com.fluxyBackend.security.access.AccessService;
import com.fluxyBackend.security.access.Member;
import com.fluxyBackend.security.access.MemberRole;
import com.fluxyBackend.security.access.Permission;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TeamService {

    private static final Duration INVITATION_TTL = Duration.ofDays(7);
    private static final Duration TRANSFER_TTL = Duration.ofHours(72);
    private static final Set<MemberRole> ASSIGNABLE = EnumSet.of(
            MemberRole.ADMIN, MemberRole.MANAGER, MemberRole.SELLER, MemberRole.WAREHOUSE, MemberRole.VIEWER);

    private final MembershipRepository membershipRepository;
    private final TeamInvitationRepository invitationRepository;
    private final OwnershipTransferRepository transferRepository;
    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final SessionService sessionService;
    private final EmailService emailService;
    private final AuditService auditService;
    private final com.fluxyBackend.billing.EntitlementService entitlements;

    @Value("${app.frontend_url:http://localhost:5173}")
    private String frontendUrl;

    // ─── Vistas ───────────────────────────────────────────────────────────────

    public record MemberView(Long userId, String fullName, String email, String role, String status,
                             List<String> permissions, boolean customPermissions, Instant joinedAt, boolean you) {}

    public record InvitationView(Long id, String email, String role, List<String> permissions, String status,
                                 Instant expiresAt, Instant createdAt) {}

    public record TransferView(Long id, Long fromUserId, String fromName, Long toUserId, String toName,
                               OffsetDateTime createdAt, OffsetDateTime expiresAt, boolean incoming) {}

    public record Team(List<MemberView> members, List<InvitationView> invitations,
                       Map<String, List<String>> roleDefaults, List<String> allPermissions,
                       TransferView pendingTransfer) {}

    public record InviteRequest(String email, String role, List<String> permissions) {}

    public record InviteResult(InvitationView invitation, String acceptUrl, boolean emailSent) {}

    public record UpdateMemberRequest(String role, List<String> permissions, Boolean useRoleDefaults, String status) {}

    public record InvitationInfo(String companyName, String email, String role, boolean valid, String reason) {}

    public record AcceptRequest(String fullName, String password) {}

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
                Arrays.stream(Permission.values()).map(Enum::name).toList(),
                pendingTransfer(companyId, actor.user().getId(), users));
    }

    // ─── Invitaciones ─────────────────────────────────────────────────────────

    @Transactional
    public InviteResult invite(Member actor, InviteRequest request) {
        // Sumar personas es del plan Pro. Gestionar a las que ya están (roles, suspender, quitar)
        // sigue disponible siempre: al bajar de plan el dueño tiene que poder recortar accesos.
        entitlements.require(actor.company(), com.fluxyBackend.billing.Feature.TEAM);
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

        String token = Hashing.randomToken();
        TeamInvitation invitation = new TeamInvitation();
        invitation.setCompanyId(actor.companyId());
        invitation.setEmail(email);
        invitation.setRole(role.name());
        invitation.setPermissions(permissions == null ? null : Permission.format(permissions));
        invitation.setTokenHash(Hashing.sha256(token));
        invitation.setExpiresAt(Instant.now().plus(INVITATION_TTL));
        invitation.setInvitedBy(actor.user().getId());
        invitationRepository.save(invitation);

        auditService.record(actor, AuditAction.TEAM_INVITED, "INVITATION", invitation.getId(),
                Map.of("email", email, "role", role.name(), "customPermissions", permissions != null));

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
        auditService.record(actor, AuditAction.TEAM_INVITE_REVOKED, "INVITATION", invitationId,
                Map.of("email", invitation.getEmail()));
    }

    public InvitationInfo invitationInfo(String token) {
        Optional<TeamInvitation> found = invitationRepository.findByTokenHash(Hashing.sha256(token));
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
    public AuthResponse accept(String token, AcceptRequest request, HttpServletRequest http) {
        TeamInvitation invitation = invitationRepository.findByTokenHash(Hashing.sha256(token))
                .filter(TeamInvitation::isPending)
                .orElseThrow(() -> new BusinessException(HttpStatus.GONE, "INVITATION_INVALID",
                        "La invitación no es válida o ya venció. Pedile a quien te invitó que la reenvíe."));
        // Una invitación enviada con plan Pro no se puede aceptar si el negocio ya bajó a Free.
        companyRepository.findById(invitation.getCompanyId())
                .ifPresent(company -> entitlements.require(company, com.fluxyBackend.billing.Feature.TEAM));
        String fullName = request.fullName() == null ? "" : request.fullName().strip().replaceAll("\\s+", " ");
        if (fullName.length() < 2 || fullName.length() > 150) throw new BusinessException("Ingresá tu nombre completo.");
        PasswordPolicy.validate(request.password(), invitation.getEmail());
        if (userRepository.existsByEmailIgnoreCase(invitation.getEmail())) {
            throw BusinessException.conflict("EMAIL_HAS_ACCOUNT", "Ese correo ya tiene una cuenta. Iniciá sesión.");
        }
        Company company = companyRepository.findById(invitation.getCompanyId())
                .orElseThrow(() -> new NotFoundException("La empresa ya no existe."));

        User user = userRepository.saveAndFlush(User.builder()
                .fullName(fullName)
                .firstName(fullName.split(" ", 2)[0])
                .email(invitation.getEmail())
                .password(passwordEncoder.encode(request.password()))
                .passwordEnabled(true)
                .passwordChangedAt(LocalDateTime.now())
                .role(Role.TEAM_MEMBER)
                .status(User.Status.ACTIVE)
                .company(company)
                .build());
        Membership membership = new Membership(user.getId(), company.getId(), invitation.getRole());
        membership.setPermissions(invitation.getPermissions());
        membership.setInvitedBy(invitation.getInvitedBy());
        membershipRepository.save(membership);
        invitation.setAcceptedAt(Instant.now());

        auditService.record(company.getId(), user, AuditAction.TEAM_MEMBER_JOINED, "USER", user.getId(),
                Map.of("role", invitation.getRole()));
        return sessionService.create(user, UserSession.AuthMethod.INVITATION, false, http);
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
        if (current == MemberRole.OWNER) {
            throw new BusinessException("No se puede modificar al dueño. Para cambiarlo, usá la transferencia de propiedad.");
        }
        ensureCanAssign(actor, current);

        Map<String, Object> changes = new LinkedHashMap<>();
        if (request.role() != null) {
            MemberRole role = assignableRole(request.role());
            ensureCanAssign(actor, role);
            if (role != current) changes.put("role", current.name() + " → " + role.name());
            membership.setRole(role.name());
        }
        if (Boolean.TRUE.equals(request.useRoleDefaults())) {
            if (membership.getPermissions() != null) changes.put("permissions", "del rol");
            membership.setPermissions(null);
        } else if (request.permissions() != null) {
            String formatted = Permission.format(grantable(actor, request.permissions()));
            if (!formatted.equals(membership.getPermissions())) changes.put("permissions", formatted);
            membership.setPermissions(formatted);
        }
        boolean disabled = false;
        if (request.status() != null) {
            String status = request.status().strip().toUpperCase(Locale.ROOT);
            if (!Membership.STATUS_ACTIVE.equals(status) && !Membership.STATUS_DISABLED.equals(status)) {
                throw new BusinessException("El estado tiene que ser ACTIVE o DISABLED.");
            }
            if (!status.equals(membership.getStatus())) changes.put("status", status);
            disabled = Membership.STATUS_DISABLED.equals(status) && membership.isActive();
            membership.setStatus(status);
        }
        membership.setUpdatedAt(Instant.now());
        membershipRepository.save(membership);
        // Desactivar cierra también sus sesiones abiertas.
        if (disabled) sessionService.revokeAllForUser(userId, null, "ACCESS_DISABLED");
        if (!changes.isEmpty()) {
            auditService.record(actor, AuditAction.TEAM_MEMBER_UPDATED, "USER", userId, changes);
        }
        User user = userRepository.findById(userId).orElseThrow(() -> new NotFoundException("Usuario no encontrado"));
        return memberView(membership, user, actor);
    }

    // ─── Transferencia de propiedad ───────────────────────────────────────────

    /** El dueño propone; la persona propuesta acepta desde su cuenta. Requiere identidad confirmada hace poco. */
    @Transactional
    public TransferView startTransfer(Member actor, Long toUserId, String sessionId) {
        if (!actor.isOwner()) throw new ForbiddenException(ForbiddenException.OWNER_ONLY, "Solo el dueño puede transferir el negocio.");
        sessionService.requireRecentAuth(sessionId, Duration.ofMinutes(10));
        if (actor.user().getId().equals(toUserId)) throw new BusinessException("Ya sos el dueño.");
        Membership target = membershipRepository.findByUserIdAndCompanyId(toUserId, actor.companyId())
                .orElseThrow(() -> new NotFoundException("Esa persona no es parte de tu equipo."));
        if (!target.isActive() || MemberRole.parse(target.getRole()) != MemberRole.ADMIN) {
            throw new BusinessException("Solo se puede transferir a un administrador activo del equipo.");
        }
        transferRepository.findByCompanyIdOrderByCreatedAtDesc(actor.companyId()).stream()
                .filter(OwnershipTransfer::isPending).forEach(t -> t.setCancelledAt(LocalDateTime.now()));

        OwnershipTransfer transfer = new OwnershipTransfer();
        transfer.setCompanyId(actor.companyId());
        transfer.setFromUserId(actor.user().getId());
        transfer.setToUserId(toUserId);
        transfer.setCreatedAt(LocalDateTime.now());
        transfer.setExpiresAt(LocalDateTime.now().plus(TRANSFER_TTL));
        transferRepository.save(transfer);

        User toUser = userRepository.findById(toUserId).orElseThrow();
        auditService.record(actor, AuditAction.OWNERSHIP_TRANSFER_STARTED, "USER", toUserId, Map.of("to", toUser.getEmail()));
        String companyName = actor.company().getName();
        String fromName = actor.displayName();
        CompletableFuture.runAsync(() -> emailService.sendOwnershipTransferRequest(toUser.getEmail(), toUser.getFullName(),
                companyName, fromName));
        return transferView(transfer, Map.of(actor.user().getId(), actor.user(), toUserId, toUser), actor.user().getId());
    }

    @Transactional
    public void cancelTransfer(Member actor) {
        OwnershipTransfer transfer = currentTransfer(actor.companyId())
                .filter(t -> t.getFromUserId().equals(actor.user().getId()) || t.getToUserId().equals(actor.user().getId()))
                .orElseThrow(() -> new NotFoundException("No hay una transferencia pendiente."));
        transfer.setCancelledAt(LocalDateTime.now());
        auditService.record(actor, AuditAction.OWNERSHIP_TRANSFER_CANCELLED, "USER", transfer.getToUserId(), null);
    }

    @Transactional
    public void acceptTransfer(Member actor, String sessionId) {
        sessionService.requireRecentAuth(sessionId, Duration.ofMinutes(10));
        OwnershipTransfer transfer = currentTransfer(actor.companyId())
                .filter(t -> t.getToUserId().equals(actor.user().getId()))
                .orElseThrow(() -> new NotFoundException("No tenés una transferencia pendiente."));
        Membership previousOwner = membershipRepository.findByUserIdAndCompanyId(transfer.getFromUserId(), actor.companyId())
                .orElseThrow(() -> new BusinessException("El dueño actual ya no forma parte del negocio."));
        if (MemberRole.parse(previousOwner.getRole()) != MemberRole.OWNER) {
            throw new BusinessException("La transferencia ya no es válida.");
        }
        Membership newOwner = actor.membership();

        previousOwner.setRole(MemberRole.ADMIN.name());
        previousOwner.setPermissions(null);
        newOwner.setRole(MemberRole.OWNER.name());
        newOwner.setPermissions(null);
        membershipRepository.save(previousOwner);
        membershipRepository.save(newOwner);

        // El rol de seguridad acompaña: avisos, cobros y facturación buscan al BUSINESS_OWNER.
        userRepository.findById(transfer.getFromUserId()).ifPresent(u -> {
            u.setRole(Role.TEAM_MEMBER);
            userRepository.save(u);
        });
        User me = actor.user();
        me.setRole(Role.BUSINESS_OWNER);
        userRepository.save(me);

        transfer.setAcceptedAt(LocalDateTime.now());
        auditService.record(actor, AuditAction.OWNERSHIP_TRANSFERRED, "USER", me.getId(),
                Map.of("fromUserId", transfer.getFromUserId()));
    }

    private Optional<OwnershipTransfer> currentTransfer(Long companyId) {
        return transferRepository.findByCompanyIdOrderByCreatedAtDesc(companyId).stream()
                .filter(OwnershipTransfer::isPending).findFirst();
    }

    private TransferView pendingTransfer(Long companyId, Long viewerId, Map<Long, User> users) {
        return currentTransfer(companyId).map(t -> transferView(t, users, viewerId)).orElse(null);
    }

    private static TransferView transferView(OwnershipTransfer t, Map<Long, User> users, Long viewerId) {
        User from = users.get(t.getFromUserId());
        User to = users.get(t.getToUserId());
        return new TransferView(t.getId(), t.getFromUserId(), from == null ? null : from.getFullName(),
                t.getToUserId(), to == null ? null : to.getFullName(),
                BusinessClock.withOffset(t.getCreatedAt()), BusinessClock.withOffset(t.getExpiresAt()),
                t.getToUserId().equals(viewerId));
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
            throw new BusinessException("Rol inválido. Usá ADMIN, MANAGER, SELLER, WAREHOUSE o VIEWER.");
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
            case MANAGER -> "Encargado";
            case SELLER -> "Vendedor";
            case WAREHOUSE -> "Almacén";
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
}
