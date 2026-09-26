package com.fluxyBackend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Company {

    // ─── Plan ────────────────────────────────────────────────────────────────
    public enum Plan {
        FREE, PRO, BUSINESS
    }

    // ─── Campos existentes ───────────────────────────────────────────────────
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private String email;
    @Column(unique = true)
    private String slug;
    private String phone;
    @Column(length = 300)
    private String address;
    @Column(length = 2000)
    private String description;
    private String primaryColor;
    @Column(length = 2048)
    private String logoUrl;
    @Column(columnDefinition = "TEXT")
    private String storeStyle;
    @Column(columnDefinition = "TEXT")
    private String paymentMethods;

    // ─── Campos nuevos de plan ───────────────────────────────────────────────
    // ─── Dominio personalizado (plan BUSINESS) ───────────────────────────────
    private String customDomain;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @ColumnDefault("'FREE'")
    @Builder.Default
    private Plan plan = Plan.FREE;

    private LocalDateTime planActivatedAt;
    private LocalDateTime planExpiresAt;

    // ─── Trial gratuito ──────────────────────────────────────────────────────
    @Column(nullable = false)
    @ColumnDefault("false")
    @Builder.Default
    private Boolean trialUsed = false;

    // ─── Ciclo de vida por inactividad ───────────────────────────────────────
    public enum Status {
        ACTIVE,
        /** 30 días sin actividad real: la tienda sigue abierta y se avisa. */
        INACTIVE,
        /** 60 días: la tienda no recibe pedidos; el dueño puede reactivarla. */
        SUSPENDED,
        /** 90 días: la tienda sale de línea. */
        ARCHIVED,
        /** Eliminación programada, por inactividad prolongada o a pedido del dueño. */
        DELETION_PENDING,
        /** Datos personales anonimizados; ya no se puede usar. */
        ANONYMIZED
    }

    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private Status status;

    /** Última acción de negocio real. Abrir el panel no cuenta. */
    private LocalDateTime lastBusinessActivityAt;
    private LocalDateTime inactiveAt;
    private LocalDateTime suspendedAt;
    private LocalDateTime archivedAt;
    private LocalDateTime deletionScheduledAt;

    /** INACTIVITY u OWNER_REQUEST. */
    @Column(length = 32)
    private String suspensionReason;

    public Status getStatus() { return status == null ? Status.ACTIVE : status; }

    /** Plan pago vigente: no se suspende por inactividad. */
    public boolean hasActivePaidPlan() {
        return plan != null && plan != Plan.FREE
                && (planExpiresAt == null || planExpiresAt.isAfter(LocalDateTime.now()));
    }

    // ─── Getters/Setters existentes (se mantienen por compatibilidad) ────────
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getPrimaryColor() { return primaryColor; }
    public void setPrimaryColor(String primaryColor) { this.primaryColor = primaryColor; }

    public String getLogoUrl() { return logoUrl; }
    public void setLogoUrl(String logoUrl) { this.logoUrl = logoUrl; }

    public String getStoreStyle() { return storeStyle; }
    public void setStoreStyle(String storeStyle) { this.storeStyle = storeStyle; }

    public String getPaymentMethods() { return paymentMethods; }
    public void setPaymentMethods(String paymentMethods) { this.paymentMethods = paymentMethods; }

    // ─── Getter/Setter customDomain ──────────────────────────────────────────
    public String getCustomDomain() { return customDomain; }
    public void setCustomDomain(String customDomain) { this.customDomain = customDomain; }

    // ─── Getters/Setters nuevos de plan ─────────────────────────────────────
    public Plan getPlan() { return plan; }
    public void setPlan(Plan plan) { this.plan = plan; }

    public LocalDateTime getPlanActivatedAt() { return planActivatedAt; }
    public void setPlanActivatedAt(LocalDateTime planActivatedAt) { this.planActivatedAt = planActivatedAt; }

    public LocalDateTime getPlanExpiresAt() { return planExpiresAt; }
    public void setPlanExpiresAt(LocalDateTime planExpiresAt) { this.planExpiresAt = planExpiresAt; }

    public boolean isTrialUsed() { return Boolean.TRUE.equals(trialUsed); }
    public void setTrialUsed(Boolean trialUsed) { this.trialUsed = trialUsed; }

    // ─── Fecha de registro ───────────────────────────────────────────────────
    private LocalDateTime createdAt;
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    @PrePersist
    private void applyDefaults() {
        if (plan == null) plan = Plan.FREE;
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    @PreUpdate
    private void onUpdate() {
        if (plan == null) plan = Plan.FREE;
    }
}
