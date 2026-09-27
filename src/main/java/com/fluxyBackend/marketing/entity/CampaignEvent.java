package com.fluxyBackend.marketing.entity;

import com.fluxyBackend.marketing.enums.CampaignChannel;
import com.fluxyBackend.marketing.enums.CampaignEventType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Un paso del embudo atribuido a una campaña. dedupeKey evita contar dos veces la misma
 * visita (por sesión y día) o el mismo pedido.
 */
@Entity
@Table(name = "campaign_events",
        uniqueConstraints = @UniqueConstraint(name = "uk_campaign_event_dedupe", columnNames = {"campaign_id", "dedupe_key"}),
        indexes = {
                @Index(name = "idx_campaign_event_campaign", columnList = "campaign_id, type, occurred_at"),
                @Index(name = "idx_campaign_event_session", columnList = "company_id, session_id, type, occurred_at"),
                @Index(name = "idx_campaign_event_order", columnList = "order_id")
        })
@Getter
@Setter
@NoArgsConstructor
public class CampaignEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CampaignEventType type;

    /** Identificador anónimo y aleatorio del navegador; no identifica a la persona. */
    @Column(name = "session_id", length = 64)
    private String sessionId;

    /** Canal por el que llegó la visita (utm_source del enlace). */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private CampaignChannel source;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "order_id")
    private Long orderId;

    /** Total del pedido al crearse (ORDER_COMPLETED). */
    private Double amount;

    @Column(name = "dedupe_key", nullable = false, length = 120)
    private String dedupeKey;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;
}
