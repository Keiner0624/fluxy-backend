package com.fluxyBackend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Historial de cambios de estado de un pedido: quién, cuándo y por qué. */
@Entity
@Table(name = "order_status_changes", indexes = {
        @Index(name = "idx_status_changes_order", columnList = "order_id")
})
@Getter
@Setter
@NoArgsConstructor
public class OrderStatusChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    /** Null en el registro de creación del pedido. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private OrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus toStatus;

    @Column(length = 300)
    private String note;

    @Column(length = 150)
    private String changedBy;

    @Column(nullable = false)
    private LocalDateTime changedAt;

    public OrderStatusChange(Long orderId, Long companyId, OrderStatus from, OrderStatus to,
                             String note, String changedBy) {
        this.orderId = orderId;
        this.companyId = companyId;
        this.fromStatus = from;
        this.toStatus = to;
        this.note = note;
        this.changedBy = changedBy;
        this.changedAt = LocalDateTime.now();
    }
}
