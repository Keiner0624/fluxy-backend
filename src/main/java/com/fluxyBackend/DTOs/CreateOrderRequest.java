package com.fluxyBackend.DTOs;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "Pedido cuyo total se calcula en el servidor con los precios del catálogo.")
public class CreateOrderRequest {
    @NotBlank
    @Size(max = 150)
    @Schema(description = "Nombre del cliente.", example = "Ana Pérez")
    public String customerName;
    @Size(max = 30)
    @Schema(description = "Teléfono de contacto.", example = "51987654321")
    public String customerPhone;
    @Size(max = 300)
    @Schema(description = "Dirección de entrega.", example = "Av. Ejemplo 123, Lima")
    public String customerAddress;
    @NotEmpty
    @Valid
    @Schema(description = "Productos y cantidades solicitadas.")
    public List<@NotNull OrderItemsRequest> items;
    @Size(max = 50)
    @Schema(description = "Código de descuento opcional.", example = "PROMO10")
    public String couponCode;
    @Size(max = 40)
    @jakarta.validation.constraints.Pattern(regexp = "^[a-z0-9_-]*$",
            message = "debe ser una clave de medio de pago, por ejemplo yape")
    @Schema(description = "Medio de pago elegido por el cliente; opcional.", example = "yape")
    public String paymentMethod;
    @jakarta.validation.constraints.Pattern(regexp = "^[A-Za-z0-9_-]{16,64}$", message = "sesión inválida")
    @Schema(description = "Id anónimo del navegador que llegó por un enlace de campaña; solo en la tienda pública. "
            + "El servidor atribuye el pedido a la última campaña vigente que vio esa sesión.")
    public String marketingSessionId;

    // ─── Comprobante electrónico (opcional) ──────────────────────────────────
    @jakarta.validation.constraints.Pattern(regexp = "^(BOLETA|FACTURA)?$", message = "debe ser BOLETA o FACTURA")
    @Schema(description = "Comprobante que pide el cliente; solo se usa si la tienda emite comprobantes.", example = "BOLETA")
    public String invoiceType;
    @Size(max = 20)
    @Schema(description = "DNI, RUC, CARNET_EXTRANJERIA, PASAPORTE o NINGUNO.", example = "DNI")
    public String buyerDocumentType;
    @Size(max = 15)
    public String buyerDocumentNumber;
    @Size(max = 200)
    @Schema(description = "Razón social (factura) o nombre para la boleta.")
    public String buyerLegalName;
    @Size(max = 300)
    public String buyerFiscalAddress;
    @Size(max = 150)
    @Schema(description = "Correo para recibir el comprobante.")
    public String buyerEmail;
}
