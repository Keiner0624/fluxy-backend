package com.fluxyBackend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Conexiones externas de una tienda, separadas de la configuración general. */
@Entity
@Table(name = "company_integrations")
@Getter
@Setter
@NoArgsConstructor
public class CompanyIntegrations {

    @Id
    private Long companyId;

    /** Si es false no se genera el enlace ni se envía el aviso de pedidos por WhatsApp. */
    private Boolean whatsappEnabled;

    /** ID de medición de Google Analytics 4, por ejemplo G-ABC123XYZ. */
    @Column(length = 32)
    private String googleAnalyticsId;

    /** ID numérico del píxel de Meta. */
    @Column(length = 32)
    private String metaPixelId;

    private LocalDateTime updatedAt;

    public CompanyIntegrations(Long companyId) {
        this.companyId = companyId;
    }

    public boolean isWhatsappEnabled() {
        return whatsappEnabled == null || whatsappEnabled;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }
}
