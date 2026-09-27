package com.fluxyBackend.marketing.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Resultados de una campaña. Con analítica completa (plan Pro) trae embudo, canales, serie
 * diaria y productos; en Free solo los totales. canExport: plan con exportación (Business).
 */
public record CampaignAnalytics(
        Long campaignId,
        OffsetDateTime from,
        OffsetDateTime to,
        int attributionDays,
        boolean full,
        boolean canExport,
        CampaignMetrics totals,
        List<FunnelStep> funnel,
        List<ChannelRow> channels,
        List<DayRow> daily,
        List<ProductRow> products,
        CouponResult coupon) {

    /** Navegadores que llegaron a cada paso; rate es respecto del paso anterior. */
    public record FunnelStep(String type, long count, double rate) {}

    /** amount: importe de los pedidos atribuidos no cancelados (confirmados y pendientes). */
    public record ChannelRow(String channel, long visits, long orders, double amount) {}

    public record DayRow(LocalDate date, long visits, long orders, double amount) {}

    public record ProductRow(Long productId, String name, long units, double revenue) {}

    /**
     * Uso del cupón de la campaña mientras estuvo vigente, venga o no del enlace.
     * attributedOrders: los que además llegaron por el enlace.
     */
    public record CouponResult(Long couponId, String code, long orders, double sales, double discount,
                               long attributedOrders) {}
}
