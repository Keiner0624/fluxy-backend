package com.fluxyBackend.marketing.enums;

/** Segmentos de clientes calculados con el historial de pedidos. */
public enum SegmentKey {
    ALL("Todos los clientes", "Clientes con al menos un pedido.", false),
    NEW("Nuevos", "Su primer pedido fue en los últimos 30 días.", false),
    INACTIVE_30("Inactivos 30 días", "No compran desde hace 30 días o más.", false),
    FREQUENT("Frecuentes", "Tienen 3 pedidos o más.", true),
    HIGH_VALUE("Alto valor", "Compraron S/ 300 o más en total.", true),
    CATEGORY_BUYERS("Compradores de categoría", "Compraron productos de una categoría.", true);

    private final String label;
    private final String rule;
    private final boolean advanced;

    SegmentKey(String label, String rule, boolean advanced) {
        this.label = label;
        this.rule = rule;
        this.advanced = advanced;
    }

    public String label() {
        return label;
    }

    public String rule() {
        return rule;
    }

    /** Requiere el plan con segmentos avanzados. */
    public boolean advanced() {
        return advanced;
    }
}
