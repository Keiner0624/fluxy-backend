package com.fluxyBackend.invoicing.enums;

/** Solo el backend pasa una configuración a ACTIVE. */
public enum ConfigurationStatus {
    /** Sin terminar de configurar. */
    DRAFT,
    /** Algo cambió (RUC, proveedor, credenciales) o falló: hay que volver a verificar o probar. */
    REQUIRES_ACTION,
    ACTIVE,
    /** Apagada por el negocio. */
    PAUSED,
    /** Detenida por Fluxy: el perfil fiscal dejó de ser válido. */
    SUSPENDED
}
