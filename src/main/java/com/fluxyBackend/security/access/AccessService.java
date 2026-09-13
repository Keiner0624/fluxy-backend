package com.fluxyBackend.security.access;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Membership;
import com.fluxyBackend.entity.Role;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.exception.ForbiddenException;
import com.fluxyBackend.repository.MembershipRepository;
import com.fluxyBackend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Resuelve quién hace la petición y qué puede hacer en su empresa.
 *
 * Toda consulta de los módulos se filtra por la empresa de este Member: el id
 * de empresa nunca se toma de lo que envía el cliente.
 */
@Service
@RequiredArgsConstructor
public class AccessService {

    private static final String REQUEST_ATTRIBUTE = AccessService.class.getName() + ".member";

    private final UserRepository userRepository;
    private final MembershipRepository membershipRepository;

    /** Correo del vendedor autenticado; vacío para anónimos y para el token de administrador. */
    public Optional<String> sellerEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserDetails details) {
            return Optional.of(details.getUsername());
        }
        return Optional.empty();
    }

    /** Member de la petición en curso, resuelto una sola vez por petición. */
    public Member current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null
                && attributes.getAttribute(REQUEST_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) instanceof Member cached) {
            return cached;
        }
        String email = sellerEmail().orElseThrow(() -> new ForbiddenException(
                ForbiddenException.NO_COMPANY, "Esta acción requiere una cuenta de vendedor."));
        Member member = resolve(email);
        if (attributes != null) {
            attributes.setAttribute(REQUEST_ATTRIBUTE, member, RequestAttributes.SCOPE_REQUEST);
        }
        return member;
    }

    /** Member actual, exigiendo todos los permisos indicados. */
    public Member require(Permission... permissions) {
        Member member = current();
        for (Permission permission : permissions) {
            if (!member.can(permission)) {
                throw missing(permission);
            }
        }
        return member;
    }

    public Member resolve(String email) {
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ForbiddenException(ForbiddenException.NO_COMPANY, "Usuario no encontrado."));
        Company company = user.getCompany();
        if (company == null) {
            throw new ForbiddenException(ForbiddenException.NO_COMPANY, "Tu cuenta no está asociada a ninguna empresa.");
        }
        Membership membership = membershipRepository.findByUserIdAndCompanyId(user.getId(), company.getId())
                .orElseGet(() -> legacyOwnerMembership(user, company));
        if (!membership.isActive()) {
            throw new ForbiddenException(ForbiddenException.ACCESS_DISABLED,
                    "Tu acceso a esta empresa fue desactivado. Consultá con el dueño del negocio.");
        }
        MemberRole role = MemberRole.parse(membership.getRole());
        return new Member(user, company, membership, role, effectivePermissions(role, membership.getPermissions()));
    }

    public static Set<Permission> effectivePermissions(MemberRole role, String customPermissions) {
        if (role == MemberRole.OWNER) {
            return EnumSet.allOf(Permission.class);
        }
        Set<Permission> permissions = customPermissions == null
                ? role.defaultPermissions()
                : Permission.parse(customPermissions);
        permissions.removeAll(Permission.OWNER_ONLY);
        return permissions;
    }

    public static ForbiddenException missing(Permission permission) {
        return new ForbiddenException(ForbiddenException.MISSING_PERMISSION,
                "No tenés permiso para esta acción (" + permission.name() + ").");
    }

    /**
     * Las cuentas creadas antes de los memberships no tienen uno. El dueño de
     * esas cuentas sigue siendo dueño: se le crea el registro la primera vez.
     */
    private Membership legacyOwnerMembership(User user, Company company) {
        if (user.getRole() != Role.BUSINESS_OWNER) {
            throw new ForbiddenException(ForbiddenException.ACCESS_DISABLED,
                    "Tu cuenta no tiene acceso a esta empresa.");
        }
        try {
            return membershipRepository.saveAndFlush(new Membership(user.getId(), company.getId()));
        } catch (DataIntegrityViolationException raceWithAnotherRequest) {
            return membershipRepository.findByUserIdAndCompanyId(user.getId(), company.getId())
                    .orElseThrow(() -> raceWithAnotherRequest);
        }
    }
}
