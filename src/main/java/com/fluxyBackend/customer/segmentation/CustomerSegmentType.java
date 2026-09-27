package com.fluxyBackend.customer.segmentation;

/** Segmentos que calcula Fluxy con el historial de compras. No se mezclan con las etiquetas manuales. */
public enum CustomerSegmentType {
    NEW("Nuevo"),
    FREQUENT("Frecuente"),
    RECURRING("Recurrente"),
    INACTIVE("Inactivo"),
    HIGH_VALUE("Alto valor");

    private final String label;

    CustomerSegmentType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
