package com.fluxyBackend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Cuenta de Google o Apple vinculada a un usuario de Fluxy. */
@Entity
@Table(name = "user_identities",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_identity_provider_subject", columnNames = {"provider", "provider_user_id"}),
                @UniqueConstraint(name = "uk_identity_user_provider", columnNames = {"user_id", "provider"})
        })
@Getter
@Setter
@NoArgsConstructor
public class UserIdentity {

    public enum Provider { GOOGLE, APPLE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Provider provider;

    /** "sub" del proveedor: estable aunque la persona cambie de correo. */
    @Column(name = "provider_user_id", nullable = false, length = 255)
    private String providerUserId;

    @Column(length = 254)
    private String email;

    @Column(nullable = false)
    private LocalDateTime linkedAt;

    private LocalDateTime lastUsedAt;

    public UserIdentity(Long userId, Provider provider, String providerUserId, String email) {
        this.userId = userId;
        this.provider = provider;
        this.providerUserId = providerUserId;
        this.email = email;
        this.linkedAt = LocalDateTime.now();
    }
}
