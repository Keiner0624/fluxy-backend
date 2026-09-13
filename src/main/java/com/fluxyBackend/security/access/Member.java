package com.fluxyBackend.security.access;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Membership;
import com.fluxyBackend.entity.User;

import java.util.Set;

/** Quién hace la petición: su usuario, su empresa y lo que puede hacer en ella. */
public record Member(User user, Company company, Membership membership, MemberRole role,
                     Set<Permission> permissions) {

    public Long companyId() {
        return company.getId();
    }

    public boolean can(Permission permission) {
        return permissions.contains(permission);
    }

    public boolean isOwner() {
        return role == MemberRole.OWNER;
    }

    /** Nombre para historiales y movimientos. */
    public String displayName() {
        String name = user.getFullName();
        return name == null || name.isBlank() ? user.getEmail() : name;
    }
}
