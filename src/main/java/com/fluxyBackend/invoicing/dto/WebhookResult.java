package com.fluxyBackend.invoicing.dto;

/** Respuesta al proveedor: duplicate si ese eventId ya se había aplicado. */
public record WebhookResult(boolean duplicate, Long documentId, String status) {
}
