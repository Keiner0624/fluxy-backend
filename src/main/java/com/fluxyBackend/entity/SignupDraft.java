package com.fluxyBackend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Datos del negocio mientras el registro no está verificado. La empresa se crea
 * recién al completar la verificación: así no quedan tiendas de correos falsos.
 */
@Entity
@Table(name = "signup_drafts", indexes = @Index(name = "idx_signup_token", columnList = "token_hash", unique = true))
@Getter
@Setter
@NoArgsConstructor
public class SignupDraft {

    @Id
    private Long userId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(length = 120)
    private String businessName;

    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private BusinessCategory category;

    @Column(length = 11)
    private String taxId;

    private LocalDateTime termsAcceptedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private UserSession.AuthMethod authMethod;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public boolean hasBusiness() {
        return businessName != null && category != null && termsAcceptedAt != null;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
