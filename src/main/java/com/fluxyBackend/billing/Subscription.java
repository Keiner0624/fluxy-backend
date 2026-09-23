package com.fluxyBackend.billing;

import com.fluxyBackend.entity.Company.Plan;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Suscripción actual de una empresa (una fila por empresa; el historial vive en
 * SubscriptionEvent y BillingPayment).
 *
 * Renovación manual: cada pago compra meses. "Cancelar" deja cancelAtPeriodEnd en true y la
 * suscripción sigue ACTIVE hasta el fin del último periodo pagado; recién ahí pasa a CANCELED.
 * Un cambio a un plan inferior ya pagado queda programado en nextPlan desde changeEffectiveAt.
 */
@Entity
@Table(name = "subscriptions", indexes = {
        @Index(name = "idx_subscription_company", columnList = "company_id", unique = true),
        @Index(name = "idx_subscription_status_end", columnList = "status, current_period_end")
})
@Getter
@Setter
@NoArgsConstructor
public class Subscription {

    public enum Status { TRIALING, ACTIVE, PAST_DUE, CANCELED, EXPIRED }

    public enum Renewal { MANUAL, AUTOMATIC }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Plan plan;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Renewal renewal = Renewal.MANUAL;

    @Column(name = "current_period_start")
    private LocalDateTime currentPeriodStart;

    @Column(name = "current_period_end")
    private LocalDateTime currentPeriodEnd;

    @Column(nullable = false)
    private boolean cancelAtPeriodEnd;

    private LocalDateTime cancellationRequestedAt;

    @Column(length = 32)
    private String cancellationReason;

    @Column(length = 500)
    private String cancellationComment;

    private LocalDateTime canceledAt;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Plan nextPlan;

    private LocalDateTime changeEffectiveAt;

    /** Fin del periodo ya pagado del plan programado. */
    private LocalDateTime nextPeriodEnd;

    @Column(length = 24)
    private String provider;

    @Column(length = 80)
    private String providerSubscriptionId;

    /** Último aviso de vencimiento enviado (7, 3 o 1 días) y para qué fecha de fin. */
    private Integer reminderStage;

    private LocalDateTime reminderFor;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Version
    private Long version;

    public Subscription(Long companyId) {
        this.companyId = companyId;
        this.plan = Plan.FREE;
        this.status = Status.EXPIRED;
    }

    /** Vigente: da acceso a su plan. */
    public boolean isLive(LocalDateTime now) {
        return (status == Status.ACTIVE || status == Status.TRIALING)
                && currentPeriodEnd != null && currentPeriodEnd.isAfter(now);
    }

    public boolean hasPendingChange() {
        return nextPlan != null && changeEffectiveAt != null && nextPeriodEnd != null;
    }

    /** Fin del último periodo pagado, contando el cambio programado. */
    public LocalDateTime paidUntil() {
        return hasPendingChange() ? nextPeriodEnd : currentPeriodEnd;
    }

    public void clearPendingChange() {
        nextPlan = null;
        changeEffectiveAt = null;
        nextPeriodEnd = null;
    }

    public void clearCancellation() {
        cancelAtPeriodEnd = false;
        cancellationRequestedAt = null;
        cancellationReason = null;
        cancellationComment = null;
    }

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
