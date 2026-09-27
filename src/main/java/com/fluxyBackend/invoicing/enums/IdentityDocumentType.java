package com.fluxyBackend.invoicing.enums;

/** Documento de identidad del cliente (catálogo 06 de SUNAT). */
public enum IdentityDocumentType {
    NINGUNO("-", "Sin documento"),
    DNI("1", "DNI"),
    CARNET_EXTRANJERIA("4", "Carnet de extranjería"),
    RUC("6", "RUC"),
    PASAPORTE("7", "Pasaporte");

    private final String sunatCode;
    private final String label;

    IdentityDocumentType(String sunatCode, String label) {
        this.sunatCode = sunatCode;
        this.label = label;
    }

    public String sunatCode() {
        return sunatCode;
    }

    public String label() {
        return label;
    }
}
