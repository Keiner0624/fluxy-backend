package com.fluxyBackend.billing;

import com.fluxyBackend.entity.Company.Plan;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Cobro de un plan confirmado por el proveedor, con el periodo que cubrió. */
@Entity
@Table(name = "billing_payments", indexes = {
        @Index(name = "idx_billing_payment_provider_id", columnList = "provider_payment_id", unique = true),
        @Index(name = "idx_billing_payment_company", columnList = "company_id, paid_at")
})
@Getter
@NoArgsConstructor
public class BillingPayment {

    public enum Kind { NEW, RENEWAL, UPGRADE, DOWNGRADE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "subscription_id")
    private Long subscriptionId;

    @Column(length = 24, nullable = false)
    private String provider;

    @Column(name = "provider_payment_id", nullable = false, length = 80)
    private String providerPaymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Plan plan;

    private int months;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 16)
    private String status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Kind kind;

    private LocalDateTime periodStart;

    private LocalDateTime periodEnd;

    /** Días del plan anterior convertidos al plan nuevo en un cambio a un plan superior. */
    private Double creditDays;

    @Column(name = "paid_at", nullable = false)
    private LocalDateTime paidAt;

    public BillingPayment(Long companyId, Long subscriptionId, String provider, String providerPaymentId, Plan plan,
                          int months, BigDecimal amount, String currency, Kind kind, LocalDateTime periodStart,
                          LocalDateTime periodEnd, Double creditDays) {
        this.companyId = companyId;
        this.subscriptionId = subscriptionId;
        this.provider = provider;
        this.providerPaymentId = providerPaymentId;
        this.plan = plan;
        this.months = months;
        this.amount = amount;
        this.currency = currency;
        this.status = "APPROVED";
        this.kind = kind;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.creditDays = creditDays;
        this.paidAt = LocalDateTime.now();
    }
}
