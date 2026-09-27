package com.fluxyBackend.invoicing.event;

/** Comprobante aceptado: dispara el correo y, si aplica, la impresión. */
public record DocumentAcceptedEvent(Long documentId, Long companyId) {
}
