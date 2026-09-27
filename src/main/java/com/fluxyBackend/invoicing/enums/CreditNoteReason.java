package com.fluxyBackend.invoicing.enums;

/** Motivos de nota de crédito por el total (catálogo 09). Los parciales quedan para una versión posterior. */
public enum CreditNoteReason {
    ANULACION("01", "Anulación de la operación"),
    ERROR_RUC("02", "Anulación por error en el RUC"),
    DEVOLUCION_TOTAL("06", "Devolución total");

    private final String sunatCode;
    private final String label;

    CreditNoteReason(String sunatCode, String label) {
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
