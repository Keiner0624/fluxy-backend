package com.fluxyBackend.marketing.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Campaña como la ve el panel. actions dice qué transiciones admite su estado;
 * metrics viene null si la persona no tiene MARKETING_ANALYTICS.
 */
public record CampaignView(
        Long id,
        String name,
        String type,
        String objective,
        Long targetId,
        String targetName,
        String targetImage,
        Long couponId,
        String couponCode,
        String channel,
        String status,
        String trackingCode,
        String title,
        String message,
        String callToAction,
        String imageUrl,
        String segment,
        Long segmentCategoryId,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        OffsetDateTime activatedAt,
        OffsetDateTime finishedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        boolean acceptingAttribution,
        List<String> actions,
        CampaignMetrics metrics) {
}
