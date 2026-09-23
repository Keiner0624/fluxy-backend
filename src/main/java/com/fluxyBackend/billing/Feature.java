package com.fluxyBackend.billing;

/** Funciones que dependen del plan. El acceso se decide solo en EntitlementService. */
public enum Feature {
    METRICS("Métricas"),
    REPORTS("Reportes"),
    COUPONS("Cupones"),
    CUSTOM_STYLE("Estilo de la tienda"),
    WHATSAPP("Pedidos por WhatsApp"),
    CUSTOM_DOMAIN("Dominio personalizado"),
    AI_DESCRIPTIONS("Descripciones con IA"),
    NO_BRANDING("Tienda sin la marca de Fluxy");

    private final String label;

    Feature(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
