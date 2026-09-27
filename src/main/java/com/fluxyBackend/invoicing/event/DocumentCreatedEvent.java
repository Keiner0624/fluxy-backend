package com.fluxyBackend.invoicing.event;

/** Comprobante guardado y listo para enviar al proveedor (fuera de la petición). */
public record DocumentCreatedEvent(Long documentId) {
}
