package com.fluxyBackend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Cobro de un pedido de la tienda. No confundir con ProcessedPayment, que
 * registra los pagos de planes de Fluxy.
 */
@Entity
@Table(name = "order_payments", indexes = {
        @Index(name = "idx_order_payments_company_created", columnList = "company_id, created_at"),
        @Index(name = "idx_order_payments_order", columnList = "order_id")
})
@Getter
@Setter
@NoArgsConstructor
public class OrderPayment {

    public enum Status { PENDING, APPROVED, REJECTED, REFUNDED }

    public enum Provider {
        /** Registrado por el negocio: efectivo, Yape, transferencia, POS. */
        MANUAL,
        MERCADO_PAGO
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Provider provider;

    /** Clave del medio de pago (efectivo, yape, tarjeta...); null si no se indicó. */
    @Column(length = 40)
    private String method;

    @Column(nullable = false)
    private Double amount;

    @Column(nullable = false)
    private Double refundedAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    /** Número de operación de Yape, del voucher o id de pago de Mercado Pago. */
    @Column(length = 120)
    private String providerReference;

    @Column(length = 300)
    private String note;

    @Column(length = 150)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
    private LocalDateTime approvedAt;

    /** Lo que efectivamente quedó cobrado: aprobado menos lo reembolsado. */
    public double netAmount() {
        if (status != Status.APPROVED && status != Status.REFUNDED) return 0;
        return amount - (refundedAmount == null ? 0 : refundedAmount);
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (refundedAmount == null) refundedAmount = 0.0;
        if (currency == null) currency = "PEN";
        if (provider == null) provider = Provider.MANUAL;
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
