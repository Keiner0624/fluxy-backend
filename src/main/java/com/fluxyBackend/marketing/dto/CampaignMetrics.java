package com.fluxyBackend.marketing.dto;

/**
 * Resultados atribuidos a una o varias campañas.
 *
 * @param visits        entradas desde enlaces de campaña (una por navegador y día)
 * @param visitors      navegadores distintos
 * @param orders        pedidos atribuidos, sin contar los cancelados
 * @param sales         importe de los pedidos atribuidos que ya son venta (confirmados en adelante)
 * @param pendingSales  importe de los atribuidos que siguen pendientes de confirmar
 * @param conversionRate pedidos / visitantes, entre 0 y 1
 * @param averageTicket  (ventas + pendientes) / pedidos
 */
public record CampaignMetrics(long visits, long visitors, long orders, long cancelledOrders, double sales,
                              double pendingSales, double conversionRate, double averageTicket) {

    public static final CampaignMetrics EMPTY = new CampaignMetrics(0, 0, 0, 0, 0, 0, 0, 0);

    public static CampaignMetrics of(long visits, long visitors, long orders, long cancelled, double sales, double pending) {
        double rate = visitors > 0 ? Math.min(1.0, (double) orders / visitors) : 0;
        double ticket = orders > 0 ? (sales + pending) / orders : 0;
        return new CampaignMetrics(visits, visitors, orders, cancelled, round(sales), round(pending),
                Math.round(rate * 10000) / 10000.0, round(ticket));
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
