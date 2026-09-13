package com.fluxyBackend.entity;

/**
 * Rol de seguridad de la cuenta. Lo que cada persona puede hacer dentro de una
 * empresa lo decide su Membership, no este rol.
 */
public enum Role {
    ADMIN,
    BUSINESS_OWNER,
    /** Persona invitada al equipo de una empresa. */
    TEAM_MEMBER
}
