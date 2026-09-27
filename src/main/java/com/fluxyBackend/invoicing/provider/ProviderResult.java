package com.fluxyBackend.invoicing.provider;

/**
 * Respuesta del proveedor. PROCESSING: lo recibió pero todavía no hay respuesta de SUNAT; se
 * vuelve a consultar más tarde.
 */
public record ProviderResult(Outcome outcome, String providerDocumentId, String message, String pdfRef,
                             String xmlRef, String cdrRef, String hash, String qrText) {

    public enum Outcome { ACCEPTED, REJECTED, PROCESSING }

    public static ProviderResult rejected(String message) {
        return new ProviderResult(Outcome.REJECTED, null, message, null, null, null, null, null);
    }
}
