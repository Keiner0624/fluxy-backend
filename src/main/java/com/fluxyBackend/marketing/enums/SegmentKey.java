package com.fluxyBackend.marketing.enums;

import com.fluxyBackend.customer.segmentation.CustomerSegmentType;

/**
 * Audiencias de Marketing. Las de comportamiento son los segmentos automáticos del CRM
 * (CustomerSegmentationService): la regla es la misma en Clientes y en Marketing. Las demás
 * filtran por un dato del cliente que se elige al armar la campaña (param).
 */
public enum SegmentKey {
    ALL("Todos los clientes", null, Param.NONE, false),
    NEW("Nuevos", CustomerSegmentType.NEW, Param.NONE, false),
    RECURRING("Recurrentes", CustomerSegmentType.RECURRING, Param.NONE, false),
    /** El nombre queda por compatibilidad con campañas guardadas; usa la regla de inactividad configurada. */
    INACTIVE_30("Inactivos", CustomerSegmentType.INACTIVE, Param.NONE, false),
    FREQUENT("Frecuentes", CustomerSegmentType.FREQUENT, Param.NONE, true),
    HIGH_VALUE("Alto valor", CustomerSegmentType.HIGH_VALUE, Param.NONE, true),
    TAG("Con una etiqueta", null, Param.TAG, true),
    SOURCE("Por origen", null, Param.SOURCE, true),
    CATEGORY_BUYERS("Compradores de categoría", null, Param.CATEGORY, true),
    PRODUCT_BUYERS("Compradores de producto", null, Param.PRODUCT, true);

    /** Dato que hay que elegir para usar la audiencia. */
    public enum Param { NONE, TAG, SOURCE, CATEGORY, PRODUCT }

    private final String label;
    private final CustomerSegmentType type;
    private final Param param;
    private final boolean advanced;

    SegmentKey(String label, CustomerSegmentType type, Param param, boolean advanced) {
        this.label = label;
        this.type = type;
        this.param = param;
        this.advanced = advanced;
    }

    public String label() {
        return label;
    }

    /** Segmento automático del CRM que representa, o null. */
    public CustomerSegmentType type() {
        return type;
    }

    public Param param() {
        return param;
    }

    /** Requiere el plan con segmentos avanzados. */
    public boolean advanced() {
        return advanced;
    }
}
