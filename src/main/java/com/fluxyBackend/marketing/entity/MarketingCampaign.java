package com.fluxyBackend.marketing.entity;

import com.fluxyBackend.marketing.enums.CampaignChannel;
import com.fluxyBackend.marketing.enums.CampaignObjective;
import com.fluxyBackend.marketing.enums.CampaignStatus;
import com.fluxyBackend.marketing.enums.CampaignType;
import com.fluxyBackend.marketing.enums.SegmentKey;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Campaña de marketing de una empresa. El trackingCode es aleatorio e identifica la campaña
 * en los enlaces públicos; el id nunca sale a la tienda.
 */
@Entity
@Table(name = "marketing_campaigns",
        uniqueConstraints = @UniqueConstraint(name = "uk_campaign_tracking_code", columnNames = "tracking_code"),
        indexes = {
                @Index(name = "idx_campaign_company_status", columnList = "company_id, status"),
                @Index(name = "idx_campaign_status_dates", columnList = "status, starts_at, ends_at")
        })
@Getter
@Setter
@NoArgsConstructor
public class MarketingCampaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(nullable = false, length = 120)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CampaignType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CampaignObjective objective;

    /** Producto o categoría promocionados; null en campañas de tienda. */
    @Column(name = "target_id")
    private Long targetId;

    /** Cupón que usa la campaña; obligatorio en las de tipo COUPON. */
    @Column(name = "coupon_id")
    private Long couponId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CampaignChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private CampaignStatus status = CampaignStatus.DRAFT;

    @Column(name = "tracking_code", nullable = false, length = 24)
    private String trackingCode;

    @Column(length = 120)
    private String title;

    @Column(length = 1000)
    private String message;

    @Column(name = "call_to_action", length = 60)
    private String callToAction;

    @Column(name = "image_url", length = 2048)
    private String imageUrl;

    /** Audiencia opcional (campañas de recuperación, mensajes uno a uno). */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private SegmentKey segment;

    /** Categoría del segmento CATEGORY_BUYERS. */
    @Column(name = "segment_category_id")
    private Long segmentCategoryId;

    @Column(name = "starts_at")
    private LocalDateTime startsAt;

    @Column(name = "ends_at")
    private LocalDateTime endsAt;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "created_by")
    private Long createdBy;

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

    /** Acepta visitas y pedidos nuevos: activa y dentro de su vigencia. */
    public boolean acceptsAttribution(LocalDateTime now) {
        return status == CampaignStatus.ACTIVE
                && (startsAt == null || !startsAt.isAfter(now))
                && (endsAt == null || endsAt.isAfter(now));
    }
}
