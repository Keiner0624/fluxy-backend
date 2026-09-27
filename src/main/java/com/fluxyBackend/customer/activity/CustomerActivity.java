package com.fluxyBackend.customer.activity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Línea de tiempo de un cliente, aparte del historial de pedidos. Guarda una descripción lista para
 * mostrar y metadatos simples (sin datos sensibles ni secretos).
 */
@Entity
@Table(name = "customer_activities",
        indexes = {
                @Index(name = "idx_customer_activity_timeline", columnList = "company_id, customer_id, created_at"),
                @Index(name = "idx_customer_activity_reference", columnList = "customer_id, type, reference_id")
        })
@Getter
@Setter
@NoArgsConstructor
public class CustomerActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private CustomerActivityType type;

    /** Pedido, campaña o reembolso relacionado. */
    @Column(name = "reference_id")
    private Long referenceId;

    @Column(nullable = false, length = 300)
    private String description;

    /** Importe asociado (pedido, reembolso), para mostrarlo sin consultar el pedido. */
    private Double amount;

    @Column(length = 150)
    private String actor;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
