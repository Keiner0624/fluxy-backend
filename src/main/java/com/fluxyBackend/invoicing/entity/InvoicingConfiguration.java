package com.fluxyBackend.invoicing.entity;

import com.fluxyBackend.invoicing.enums.ConfigurationStatus;
import com.fluxyBackend.invoicing.enums.IssueTrigger;
import com.fluxyBackend.invoicing.enums.ProviderCode;
import com.fluxyBackend.invoicing.enums.TaxAffectation;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Configuración de facturación electrónica de una empresa (el documento la llama BillingConfiguration;
 * acá "billing" ya es la suscripción que el negocio le paga a Fluxy).
 *
 * El token del proveedor se guarda cifrado (SecretCipher) y nunca sale en una respuesta.
 */
@Entity
@Table(name = "invoicing_configurations",
        uniqueConstraints = @UniqueConstraint(name = "uk_invoicing_config_company", columnNames = "company_id"))
@Getter
@Setter
@NoArgsConstructor
public class InvoicingConfiguration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    /** RUC verificado (copia de TaxProfile.ruc). */
    @Column(name = "tax_id", length = 11)
    private String taxId;

    @Column(name = "business_name", length = 200)
    private String businessName;

    @Column(name = "trade_name", length = 200)
    private String tradeName;

    @Column(name = "fiscal_address", length = 300)
    private String fiscalAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ProviderCode provider = ProviderCode.SANDBOX;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConfigurationStatus status = ConfigurationStatus.DRAFT;

    /** Motivo del estado cuando no está activa. */
    @Column(name = "status_reason", length = 300)
    private String statusReason;

    @Column(name = "automatic_issuing", nullable = false)
    private boolean automaticIssuing;

    @Enumerated(EnumType.STRING)
    @Column(name = "issue_trigger", nullable = false, length = 24)
    private IssueTrigger issueTrigger = IssueTrigger.PAYMENT_CONFIRMED;

    @Column(name = "email_enabled", nullable = false)
    private boolean emailEnabled = true;

    @Column(name = "attach_pdf", nullable = false)
    private boolean attachPdf = true;

    @Column(name = "attach_xml", nullable = false)
    private boolean attachXml;

    @Column(name = "print_automatically", nullable = false)
    private boolean printAutomatically;

    /** Ancho del ticket: 80 o 58 mm. */
    @Column(name = "paper_width", nullable = false)
    private int paperWidth = 80;

    @Enumerated(EnumType.STRING)
    @Column(name = "tax_affectation", nullable = false, length = 12)
    private TaxAffectation taxAffectation = TaxAffectation.GRAVADO;

    /** Ruta del proveedor (Nubefact: URL propia de la cuenta). */
    @Column(name = "provider_endpoint", length = 300)
    private String providerEndpoint;

    @Column(name = "provider_token_cipher", length = 1000)
    private String providerTokenCipher;

    /** Últimos 4 caracteres, para reconocer qué token está cargado. */
    @Column(name = "provider_token_hint", length = 8)
    private String providerTokenHint;

    /** Resultado de la última prueba de conexión; se borra al cambiar proveedor o credenciales. */
    @Column(name = "connection_ok", nullable = false)
    private boolean connectionOk;

    @Column(name = "connection_checked_at")
    private LocalDateTime connectionCheckedAt;

    @Column(name = "connection_message", length = 300)
    private String connectionMessage;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Version
    private Long version;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
