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
}
