package com.fluxyBackend.invoicing.provider;

/** Credenciales ya descifradas de un negocio. Vive solo en memoria durante la llamada; toString no las muestra. */
public record ProviderContext(String endpoint, String token, String issuerRuc) {

    @Override
    public String toString() {
        return "ProviderContext[endpoint=" + endpoint + ", token=***, issuerRuc=" + issuerRuc + "]";
    }
}
