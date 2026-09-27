package com.fluxyBackend.invoicing.dto;

/** Proveedor y, si lo necesita, su ruta y token. El token se cifra al guardarlo y nunca se devuelve. */
public record ProviderSettingsRequest(String provider, String endpoint, String token) {

    @Override
    public String toString() {
        return "ProviderSettingsRequest[provider=" + provider + ", endpoint=" + endpoint + ", token=***]";
    }
}
