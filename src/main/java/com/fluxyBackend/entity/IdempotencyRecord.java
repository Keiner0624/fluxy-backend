package com.fluxyBackend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Resultado de una operación hecha con Idempotency-Key, para devolverlo si se repite. */
@Entity
@Table(name = "idempotency_records",
        uniqueConstraints = @UniqueConstraint(name = "uk_idempotency_scope_key", columnNames = {"scope", "idem_key"}),
        indexes = @Index(name = "idx_idempotency_expires", columnList = "expires_at"))
@Getter
@Setter
@NoArgsConstructor
public class IdempotencyRecord {

    public enum Status { IN_PROGRESS, COMPLETED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String scope;

    @Column(name = "idem_key", nullable = false, length = 100)
    private String key;

    /** Hash del pedido original: la misma clave con otro contenido es un error del cliente. */
    @Column(nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Status status;

    private Integer responseStatus;

    @Column(columnDefinition = "TEXT")
    private String responseBody;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
}
