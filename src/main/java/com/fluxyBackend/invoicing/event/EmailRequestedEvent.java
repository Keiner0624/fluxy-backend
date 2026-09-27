package com.fluxyBackend.invoicing.event;

/** Hay un correo de comprobante pendiente (primer envío o reenvío manual). */
public record EmailRequestedEvent(Long documentId) {
}
