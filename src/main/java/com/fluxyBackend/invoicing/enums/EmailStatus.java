package com.fluxyBackend.invoicing.enums;

/** Envío del comprobante por correo. Reenviar nunca vuelve a emitir el documento. */
public enum EmailStatus {
    /** Sin correo del cliente o envío desactivado. */
    NOT_REQUESTED,
    PENDING,
    SENT,
    /** Reservados para cuando el proveedor de correo informe la entrega por webhook. */
    DELIVERED,
    BOUNCED,
    FAILED
}
