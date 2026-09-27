package com.fluxyBackend.invoicing.entity;

import com.fluxyBackend.invoicing.enums.TaxRegime;
import com.fluxyBackend.invoicing.enums.VerificationStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Perfil fiscal verificado en el servidor. canIssueReceipt, canIssueInvoice, electronicIssuer y
 * verificationStatus los calcula el backend; ninguna petición del panel los escribe.
 */
@Entity
@Table(name = "tax_profiles",
        uniqueConstraints = @UniqueConstraint(name = "uk_tax_profile_company", columnNames = "company_id"))
@Getter
@Setter
@NoArgsConstructor
public class TaxProfile {

    /** Fuente de la verificación. */
    public static final String SOURCE_SANDBOX = "SANDBOX";
    public static final String SOURCE_RUC_API = "RUC_API";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(length = 11)
    private String ruc;

    @Column(name = "business_name", length = 200)
    private String businessName;

    /** Estado del contribuyente según la fuente (ACTIVO, BAJA DE OFICIO...). */
    @Column(name = "ruc_status", length = 40)
    private String rucStatus;

    /** Condición del domicilio (HABIDO, NO HALLADO...). */
    @Column(name = "ruc_condition", length = 40)
    private String rucCondition;

    @Column(name = "fiscal_address", length = 300)
    private String fiscalAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "tax_regime", nullable = false, length = 12)
    private TaxRegime taxRegime = TaxRegime.UNKNOWN;

    @Column(name = "electronic_issuer", nullable = false)
    private boolean electronicIssuer;

    @Column(name = "can_issue_receipt", nullable = false)
    private boolean canIssueReceipt;

    @Column(name = "can_issue_invoice", nullable = false)
    private boolean canIssueInvoice;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 24)
    private VerificationStatus verificationStatus = VerificationStatus.PENDING_VERIFICATION;

    @Column(name = "verification_message", length = 300)
    private String verificationMessage;

    @Column(name = "verification_source", length = 16)
    private String verificationSource;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "last_checked_at")
    private LocalDateTime lastCheckedAt;

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

    public boolean verified() {
        return verificationStatus == VerificationStatus.VERIFIED;
    }
}
