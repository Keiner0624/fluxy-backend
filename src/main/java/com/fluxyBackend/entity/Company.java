package com.fluxyBackend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
    private String address;
    private String description;
    private String primaryColor;
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
    @Builder.Default
    private Plan plan = Plan.FREE;

    private LocalDateTime planActivatedAt;
    private LocalDateTime planExpiresAt;

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
}
