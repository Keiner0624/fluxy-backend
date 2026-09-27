package com.fluxyBackend.invoicing.entity;

import com.fluxyBackend.invoicing.enums.*;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Comprobante electrónico. Los datos del emisor, del receptor y los ítems son una copia del
 * momento de la venta: si mañana cambia el cliente o el producto, el comprobante no cambia.
 *
 * La fila también es la cola de trabajo: status + nextAttemptAt dicen qué falta enviar, así
 * que un reinicio no pierde emisiones ni correos (hace de outbox).
 */
@Entity
@Table(name = "electronic_documents",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_document_number", columnNames = {"company_id", "environment", "series", "number"}),
                @UniqueConstraint(name = "uk_document_public_token", columnNames = "public_token")
        },
        indexes = {
                @Index(name = "idx_document_company_created", columnList = "company_id, created_at"),
                @Index(name = "idx_document_order", columnList = "company_id, order_id"),
                @Index(name = "idx_document_work", columnList = "status, next_attempt_at"),
                @Index(name = "idx_document_email_work", columnList = "email_status, email_next_attempt_at"),
                @Index(name = "idx_document_provider_ref", columnList = "provider, provider_document_id")
        })
@Getter
@Setter
@NoArgsConstructor
public class ElectronicDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "customer_id")
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DocumentType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Environment environment;

    @Column(nullable = false, length = 4)
    private String series;

    @Column(nullable = false)
    private Long number;

    // ─── Emisor (copia) ──────────────────────────────────────────────────────
    @Column(name = "issuer_ruc", nullable = false, length = 11)
    private String issuerRuc;

    @Column(name = "issuer_name", nullable = false, length = 200)
    private String issuerName;

    @Column(name = "issuer_trade_name", length = 200)
    private String issuerTradeName;

    @Column(name = "issuer_address", length = 300)
    private String issuerAddress;

    // ─── Receptor (copia) ────────────────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(name = "customer_document_type", nullable = false, length = 20)
    private IdentityDocumentType customerDocumentType;

    @Column(name = "customer_document_number", length = 15)
    private String customerDocumentNumber;

    @Column(name = "customer_name", nullable = false, length = 200)
    private String customerName;

    @Column(name = "customer_email", length = 150)
    private String customerEmail;

    @Column(name = "customer_address", length = 300)
    private String customerAddress;

    // ─── Importes ────────────────────────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(name = "tax_affectation", nullable = false, length = 12)
    private TaxAffectation taxAffectation;

    /** Base imponible (sin IGV). */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal subtotal;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal tax;

    /** Descuento del pedido ya repartido en los ítems. */
    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal discount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal total;

    @Column(nullable = false, length = 3)
    private String currency = "PEN";

    // ─── Estado ──────────────────────────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private DocumentStatus status = DocumentStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ProviderCode provider;

    @Column(name = "provider_document_id", length = 120)
    private String providerDocumentId;

    /** Referencias a los archivos en el proveedor. Nunca se devuelven al panel: se descargan a través de Fluxy. */
    @Column(name = "pdf_storage_key", length = 600)
    private String pdfStorageKey;

    @Column(name = "xml_storage_key", length = 600)
    private String xmlStorageKey;

    @Column(name = "cdr_storage_key", length = 600)
    private String cdrStorageKey;

    @Column(name = "hash_code", length = 120)
    private String hashCode;

    @Column(name = "qr_text", length = 600)
    private String qrText;

    /** Último mensaje del proveedor o de SUNAT. */
    @Column(name = "provider_message", length = 600)
    private String providerMessage;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "last_error", length = 600)
    private String lastError;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @Column(name = "rejected_at")
    private LocalDateTime rejectedAt;

    // ─── Correo ──────────────────────────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @Column(name = "email_status", nullable = false, length = 16)
    private EmailStatus emailStatus = EmailStatus.NOT_REQUESTED;

    @Column(name = "email_attempts", nullable = false)
    private int emailAttempts;

    @Column(name = "email_next_attempt_at")
    private LocalDateTime emailNextAttemptAt;

    @Column(name = "email_sent_at")
    private LocalDateTime emailSentAt;

    // ─── Nota de crédito ─────────────────────────────────────────────────────
    /** En una nota de crédito: el comprobante que modifica. */
    @Column(name = "related_document_id")
    private Long relatedDocumentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "credit_reason", length = 20)
    private CreditNoteReason creditReason;

    @Column(name = "credit_description", length = 250)
    private String creditDescription;

    /** En un comprobante de venta: la nota de crédito que lo anula. */
    @Column(name = "credit_note_id")
    private Long creditNoteId;

    /** Token aleatorio del enlace público (QR y correo). Revocable; no es el id. */
    @Column(name = "public_token", length = 64)
    private String publicToken;

    @Column(name = "created_by", length = 150)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Version
    private Long version;

    @OneToMany(mappedBy = "document", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("lineNumber ASC")
    private List<ElectronicDocumentItem> items = new ArrayList<>();

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public String fullNumber() {
        return series + "-" + String.format("%08d", number);
    }

    public void addItem(ElectronicDocumentItem item) {
        item.setDocument(this);
        items.add(item);
    }
}
