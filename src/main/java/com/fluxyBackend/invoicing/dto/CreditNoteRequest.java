package com.fluxyBackend.invoicing.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Nota de crédito por el total del comprobante. */
public record CreditNoteRequest(
        @Schema(description = "ANULACION, ERROR_RUC (solo facturas) o DEVOLUCION_TOTAL", example = "ANULACION") String reason,
        @Schema(description = "Detalle opcional", example = "Pedido anulado por el cliente") String description) {
}
