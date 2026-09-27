package com.fluxyBackend.domain;

/** Estado del dominio propio de una tienda, según la última consulta a Vercel. */
public enum CustomDomainStatus {
    /** Conectado en Vercel; faltan (o todavía no se propagaron) los registros DNS. */
    PENDING_DNS("Esperando los registros DNS"),
    /** El dominio ya está en otra cuenta de Vercel: hay que probar que es tuyo con un registro TXT. */
    VERIFICATION_REQUIRED("Falta verificar que el dominio es tuyo"),
    /** Los DNS apuntan a Fluxy: la tienda se abre en el dominio, con HTTPS. */
    ACTIVE("Activo"),
    /** Vercel rechazó el dominio o no se pudo consultar. */
    ERROR("Con error");

    private final String label;

    CustomDomainStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
