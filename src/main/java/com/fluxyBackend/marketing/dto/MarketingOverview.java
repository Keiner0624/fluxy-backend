package com.fluxyBackend.marketing.dto;

import java.time.OffsetDateTime;

/**
 * Resumen del módulo: resultados del periodo, campañas en curso frente al límite del plan y
 * qué incluye el plan. Los límites salen de PlanCatalog, no del panel.
 */
public record MarketingOverview(OffsetDateTime from, OffsetDateTime to, CampaignMetrics totals,
                                long liveCampaigns, int liveCampaignLimit, String plan, Capabilities capabilities) {

    public record Capabilities(boolean fullAnalytics, boolean advancedSegments, boolean customQr, boolean export,
                               boolean coupons) {}
}
