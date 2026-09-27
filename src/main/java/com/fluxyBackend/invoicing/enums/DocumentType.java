package com.fluxyBackend.invoicing.enums;

/** Comprobantes que emite el negocio. sunatCode es el catálogo 01 de SUNAT. */
public enum DocumentType {
    FACTURA("01", "Factura electrónica", 'F'),
    BOLETA("03", "Boleta de venta electrónica", 'B'),
    NOTA_CREDITO("07", "Nota de crédito electrónica", ' ');

    private final String sunatCode;
    private final String label;
    private final char seriesPrefix;

    DocumentType(String sunatCode, String label, char seriesPrefix) {
        this.sunatCode = sunatCode;
        this.label = label;
        this.seriesPrefix = seriesPrefix;
    }

    public String sunatCode() {
        return sunatCode;
    }

    public String label() {
        return label;
    }

    /** Letra con la que empieza la serie; la nota de crédito toma la del comprobante que modifica. */
    public char seriesPrefix() {
        return seriesPrefix;
    }

    public boolean isSale() {
        return this != NOTA_CREDITO;
    }
}
