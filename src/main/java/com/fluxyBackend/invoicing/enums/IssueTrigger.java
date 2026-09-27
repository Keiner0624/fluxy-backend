package com.fluxyBackend.invoicing.enums;

/** Cuándo se emite solo, si la emisión es automática. */
public enum IssueTrigger {
    /** El pedido quedó pagado por completo. */
    PAYMENT_CONFIRMED,
    /** El pedido se entregó. */
    ORDER_DELIVERED
}
