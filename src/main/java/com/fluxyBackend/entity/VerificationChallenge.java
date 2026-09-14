package com.fluxyBackend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Código de verificación enviado por correo o WhatsApp. Se guarda el HMAC del
 * código, nunca el código: quien lea la base no puede usarlo.
 */
@Entity
@Table(name = "verification_challenges", indexes = {
        @Index(name = "idx_challenges_destination", columnList = "destination_hash, created_at"),
        @Index(name = "idx_challenges_user", columnList = "user_id, purpose"),
        @Index(name = "idx_challenges_ip", columnList = "ip_hash, created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class VerificationChallenge {

    public enum Type { EMAIL, PHONE }

    public enum Purpose { SIGN_UP, VERIFY_EMAIL, CHANGE_EMAIL, CHANGE_PHONE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Purpose purpose;

    @Column(name = "destination_hash", nullable = false, length = 64)
    private String destinationHash;

    /** "e***o@gmail.com" o "+51 9** *** 777", para mostrar a dónde se envió. */
    @Column(length = 80)
    private String destinationMasked;

    /**
     * Correo o teléfono nuevo en los cambios de contacto: se aplica recién al
     * verificar. Se borra al consumir el código.
     */
    @Column(length = 254)
    private String pendingValue;

    @Column(nullable = false, length = 64)
    private String codeHash;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false)
    private int attempts;

    @Column(nullable = false)
    private int maxAttempts;

    private LocalDateTime consumedAt;
    private LocalDateTime invalidatedAt;
    private LocalDateTime deliveryFailedAt;

    @Column(name = "ip_hash", length = 64)
    private String ipHash;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public boolean isOpen() {
        return consumedAt == null && invalidatedAt == null;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
