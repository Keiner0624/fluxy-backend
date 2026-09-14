package com.fluxyBackend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Sesión de un dispositivo. El access token (15 min) lleva su id; el refresh
 * token se guarda solo como hash y rota en cada uso.
 */
@Entity
@Table(name = "user_sessions", indexes = {
        @Index(name = "idx_sessions_user", columnList = "user_id"),
        @Index(name = "idx_sessions_refresh", columnList = "refresh_token_hash", unique = true),
        @Index(name = "idx_sessions_previous", columnList = "previous_refresh_token_hash")
})
@Getter
@Setter
@NoArgsConstructor
public class UserSession {

    public enum AuthMethod { PASSWORD, GOOGLE, APPLE, SIGNUP, INVITATION }

    /** UUID: va en el access token como sid. */
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "refresh_token_hash", nullable = false, length = 64)
    private String refreshTokenHash;

    /** Hash anterior a la última rotación: si alguien lo reutiliza, el token fue robado. */
    @Column(name = "previous_refresh_token_hash", length = 64)
    private String previousRefreshTokenHash;

    private LocalDateTime rotatedAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private AuthMethod authMethod;

    private boolean rememberMe;

    @Column(length = 300)
    private String userAgent;

    /** "Chrome en Windows": se muestra en la lista de dispositivos. */
    @Column(length = 120)
    private String deviceLabel;

    /** Ubicación aproximada: solo el prefijo de la IP, nunca la dirección completa. */
    @Column(length = 45)
    private String ipPrefix;

    /** Última vez que la persona confirmó su identidad, para acciones sensibles. */
    private LocalDateTime authenticatedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime lastUsedAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    private LocalDateTime revokedAt;

    @Column(length = 40)
    private String revokeReason;

    public boolean isActive() {
        return revokedAt == null && expiresAt.isAfter(LocalDateTime.now());
    }
}
