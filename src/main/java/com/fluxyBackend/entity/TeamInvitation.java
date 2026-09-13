package com.fluxyBackend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Invitación a sumarse al equipo de una empresa. Se guarda el hash del token,
 * nunca el token: quien lea la base no puede aceptar invitaciones ajenas.
 */
@Entity
@Table(name = "team_invitations", indexes = {
        @Index(name = "idx_invitations_company", columnList = "company_id"),
        @Index(name = "idx_invitations_token", columnList = "token_hash", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
public class TeamInvitation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(nullable = false, length = 150)
    private String email;

    @Column(nullable = false, length = 16)
    private String role;

    @Column(length = 1000)
    private String permissions;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant acceptedAt;
    private Instant revokedAt;
    private Long invitedBy;

    @Column(nullable = false)
    private Instant createdAt;

    public boolean isPending() {
        return acceptedAt == null && revokedAt == null && Instant.now().isBefore(expiresAt);
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
