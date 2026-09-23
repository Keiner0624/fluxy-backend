package com.fluxyBackend.billing;

import com.fluxyBackend.entity.Company.Plan;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Historial de la suscripción: cada alta, renovación, cambio, cancelación y vencimiento. */
@Entity
@Table(name = "subscription_events", indexes = @Index(name = "idx_subscription_events_company", columnList = "company_id, created_at"))
@Getter
@NoArgsConstructor
public class SubscriptionEvent {

    public enum Type {
        TRIAL_STARTED, SUBSCRIPTION_STARTED, RENEWED, PLAN_UPGRADED, DOWNGRADE_SCHEDULED, DOWNGRADE_APPLIED,
        CANCELLATION_REQUESTED, CANCELLATION_REVOKED, SUBSCRIPTION_CANCELED, SUBSCRIPTION_EXPIRED, ADMIN_GRANTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Plan fromPlan;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Plan toPlan;

    private LocalDateTime effectiveAt;

    private Long actorUserId;

    @Column(length = 150)
    private String actorLabel;

    @Column(length = 500)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public SubscriptionEvent(Long companyId, Type type, Plan fromPlan, Plan toPlan, LocalDateTime effectiveAt,
                             Long actorUserId, String actorLabel, String detail) {
        this.companyId = companyId;
        this.type = type;
        this.fromPlan = fromPlan;
        this.toPlan = toPlan;
        this.effectiveAt = effectiveAt;
        this.actorUserId = actorUserId;
        this.actorLabel = actorLabel;
        this.detail = detail == null ? null : detail.substring(0, Math.min(detail.length(), 500));
        this.createdAt = LocalDateTime.now();
    }
}
