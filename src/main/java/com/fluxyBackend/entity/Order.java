package com.fluxyBackend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String customerName;
    private String customerPhone;
    @Column(length = 300)
    private String customerAddress;
    private Double total;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ─── Cupón aplicado ───────────────────────────────────────────────────────
    private String couponCode;
    private Double discountAmount;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    /** Clave del medio de pago elegido al comprar (efectivo, yape, tarjeta...). */
    @Column(length = 40)
    private String paymentMethod;

    @Column(length = 300)
    private String cancelReason;

    // ─── Comprobante pedido por el cliente (ya validado) ─────────────────────
    /** BOLETA o FACTURA; null si no pidió comprobante al comprar. */
    @Column(name = "invoice_type", length = 16)
    private String invoiceType;

    @Column(name = "buyer_document_type", length = 20)
    private String buyerDocumentType;

    @Column(name = "buyer_document_number", length = 15)
    private String buyerDocumentNumber;

    /** Razón social (factura) o nombre para la boleta. */
    @Column(name = "buyer_legal_name", length = 200)
    private String buyerLegalName;

    @Column(name = "buyer_fiscal_address", length = 300)
    private String buyerFiscalAddress;

    @Column(name = "buyer_email", length = 150)
    private String buyerEmail;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = true)
    @JsonIgnore
    private User owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    @JsonIgnore
    private Customer customer;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items;

    @ManyToOne
    @JoinColumn(name = "company_id")
    private Company company;

    /** Solo el id: serializar el cliente completo arrastraría sus notas internas. */
    public Long getCustomerId() {
        return customer == null ? null : customer.getId();
    }

    @PrePersist
    @PreUpdate
    void touch() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }
}
