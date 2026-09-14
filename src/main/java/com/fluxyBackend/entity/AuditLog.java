package com.fluxyBackend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Acciones relevantes para reconstruir qué pasó: accesos, cambios sensibles y
 * operaciones irreversibles. No se audita cada lectura.
 */
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_company_created", columnList = "company_id, created_at"),
        @Index(name = "idx_audit_actor", columnList = "actor_user_id, created_at"),
        @Index(name = "idx_audit_action", columnList = "action, created_at")
})
@Getter
@Setter
@NoArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id")
    private Long companyId;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    /** Nombre o correo de quien actuó, tal como era en ese momento. */
    @Column(length = 150)
    private String actorLabel;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(length = 40)
    private String entityType;

    @Column(length = 64)
    private String entityId;

    /** JSON con el detalle. Nunca contraseñas, códigos ni tokens. */
    @Column(columnDefinition = "TEXT")
    private String metadata;

    /** Hash de la IP: permite correlacionar sin guardar la dirección. */
    @Column(length = 64)
    private String ipHash;

    @Column(length = 64)
    private String requestId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
