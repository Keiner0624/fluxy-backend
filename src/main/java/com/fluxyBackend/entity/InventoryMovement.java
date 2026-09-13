package com.fluxyBackend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Cada cambio de stock queda registrado con el antes y el después.
 *
 * El producto se guarda como id y nombre, sin clave foránea: el historial tiene
 * que sobrevivir aunque el producto se elimine.
 */
@Entity
@Table(name = "inventory_movements", indexes = {
        @Index(name = "idx_movements_company_created", columnList = "company_id, created_at"),
        @Index(name = "idx_movements_product", columnList = "product_id")
})
@Getter
@Setter
@NoArgsConstructor
public class InventoryMovement {

    public enum Type {
        /** Stock con el que se creó el producto, o el que tenía al activar el inventario. */
        INITIAL,
        /** Mercadería que entra: compra, reposición, devolución de proveedor. */
        ENTRY,
        /** Mercadería que sale sin venta: merma, consumo interno, pérdida. */
        EXIT,
        /** Corrección tras un conteo físico. */
        ADJUSTMENT,
        /** Salida por un pedido. */
        SALE,
        /** Devolución al stock por un pedido cancelado. */
        CANCELLATION
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(length = 200)
    private String productName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Type type;

    /** Cambio con signo: positivo entra, negativo sale. */
    @Column(nullable = false)
    private Integer quantity;

    @Column(nullable = false)
    private Integer stockBefore;

    @Column(nullable = false)
    private Integer stockAfter;

    @Column(length = 300)
    private String reason;

    /** Origen del movimiento, por ejemplo "Pedido #120". */
    @Column(length = 80)
    private String reference;

    private Long orderId;

    @Column(length = 150)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
