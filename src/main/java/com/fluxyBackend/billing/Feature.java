package com.fluxyBackend.billing;

/** Funciones que dependen del plan. El acceso se decide solo en EntitlementService. */
public enum Feature {
    METRICS("Métricas"),
    REPORTS("Reportes"),
    COUPONS("Cupones"),
    TEAM("Equipo con roles y permisos"),
    CUSTOM_STYLE("Estilo de la tienda"),
    WHATSAPP("Pedidos por WhatsApp"),
    CUSTOM_DOMAIN("Dominio personalizado"),
    AI_DESCRIPTIONS("Textos con IA"),
    NO_BRANDING("Tienda sin la marca de Fluxy"),
    MARKETING_FULL_ANALYTICS("Analítica completa de campañas"),
    MARKETING_ADVANCED_SEGMENTS("Segmentos avanzados de clientes"),
    MARKETING_CUSTOM_QR("QR de campaña personalizado"),
    MARKETING_EXPORT("Exportación de resultados de campañas"),
    ELECTRONIC_INVOICING("Facturación electrónica");

    private final String label;

    Feature(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
