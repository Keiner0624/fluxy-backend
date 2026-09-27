package com.fluxyBackend.customer;

/** Por dónde llegó el cliente la primera vez. */
public enum CustomerSource {
    ONLINE_STORE("Tienda online"),
    POS("Punto de venta"),
    MANUAL("Registro manual"),
    IMPORT("Importación"),
    WHATSAPP("WhatsApp"),
    INSTAGRAM("Instagram"),
    FACEBOOK("Facebook"),
    CAMPAIGN("Campaña");

    private final String label;

    CustomerSource(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
