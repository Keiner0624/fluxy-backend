package com.fluxyBackend.invoicing.enums;

/** Quién emite. SANDBOX no envía nada a SUNAT: sirve para probar el flujo completo. */
public enum ProviderCode {
    SANDBOX(Environment.TEST, "Modo de prueba de Fluxy"),
    NUBEFACT(Environment.PRODUCTION, "Nubefact");

    private final Environment environment;
    private final String label;

    ProviderCode(Environment environment, String label) {
        this.environment = environment;
        this.label = label;
    }

    public Environment environment() {
        return environment;
    }

    public String label() {
        return label;
    }
}
