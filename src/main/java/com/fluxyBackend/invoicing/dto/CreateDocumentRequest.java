package com.fluxyBackend.invoicing.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Emitir boleta o factura de un pedido. Los datos del cliente son opcionales: si no vienen se usan
 * los que dejó al comprar. El servidor los valida y decide serie y número.
 */
@Schema(description = "Comprobante de venta para un pedido.")
public record CreateDocumentRequest(
        Long orderId,
        @Schema(description = "BOLETA o FACTURA", example = "BOLETA") String type,
        @Schema(description = "Serie a usar; vacío para la primera habilitada", example = "B001") String series,
        @Schema(description = "DNI, RUC, CARNET_EXTRANJERIA, PASAPORTE o NINGUNO") String customerDocumentType,
        String customerDocumentNumber,
        String customerName,
        String customerAddress,
        String customerEmail) {
}
