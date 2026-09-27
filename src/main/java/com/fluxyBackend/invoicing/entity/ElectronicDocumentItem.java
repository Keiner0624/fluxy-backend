package com.fluxyBackend.invoicing.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** Línea del comprobante: fotografía de la venta, no una referencia viva al producto. */
@Entity
@Table(name = "electronic_document_items",
        indexes = @Index(name = "idx_document_item_document", columnList = "document_id"))
@Getter
@Setter
@NoArgsConstructor
public class ElectronicDocumentItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private ElectronicDocument document;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @Column(name = "product_id")
    private Long productId;

    @Column(length = 60)
    private String sku;

    @Column(nullable = false, length = 250)
    private String description;

    @Column(nullable = false, precision = 14, scale = 3)
    private BigDecimal quantity;

    /** Precio unitario con impuesto, ya con el descuento repartido. */
    @Column(name = "unit_price", nullable = false, precision = 20, scale = 10)
    private BigDecimal unitPrice;

    /** Valor unitario sin impuesto. */
    @Column(name = "unit_value", nullable = false, precision = 20, scale = 10)
    private BigDecimal unitValue;

    /** Base imponible de la línea. */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal subtotal;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal tax;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal total;
}
