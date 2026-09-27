package com.fluxyBackend.entity;

import com.fluxyBackend.customer.CustomerSource;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Comprador de una tienda. Se crea solo al recibir un pedido y se reconoce por
 * su teléfono, o por su nombre cuando no dejó teléfono.
 */
@Entity
@Table(name = "customers",
        uniqueConstraints = @UniqueConstraint(name = "uk_customers_company_phone",
                columnNames = {"company_id", "phone_key"}),
        indexes = {
                @Index(name = "idx_customers_company_name", columnList = "company_id, name_key"),
                @Index(name = "idx_customers_company_source", columnList = "company_id, source"),
                @Index(name = "idx_customers_company_opt_out", columnList = "company_id, marketing_opt_out")
        })
@Getter
@Setter
@NoArgsConstructor
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 30)
    private String phone;

    /** Solo dígitos, con prefijo de país: identifica al cliente. */
    @Column(name = "phone_key", length = 20)
    private String phoneKey;

    /** Nombre normalizado, para reconocer clientes que no dejaron teléfono. */
    @Column(name = "name_key", length = 150)
    private String nameKey;

    @Column(length = 150)
    private String email;

    @Column(length = 300)
    private String address;

    /** Notas internas del negocio. Nunca se muestran al cliente. */
    @Column(columnDefinition = "TEXT")
    private String notes;

    /** Etiquetas separadas por coma, en minúsculas. */
    @Column(length = 400)
    private String tags;

    /**
     * Pidió no recibir promociones: queda fuera de los segmentos de Marketing.
     * Nullable para que la columna se agregue sin valor por defecto; null = sin baja.
     */
    @Column(name = "marketing_opt_out")
    private Boolean marketingOptOut;

    /** Por dónde llegó; lo completa CustomerDataBackfill para los clientes anteriores al CRM. */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private CustomerSource source;

    /** Campaña que trajo su primera compra (solo si source = CAMPAIGN). */
    @Column(name = "source_campaign_id")
    private Long sourceCampaignId;

    public boolean optedOutOfMarketing() {
        return Boolean.TRUE.equals(marketingOptOut);
    }

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
