package com.fluxyBackend.DTOs;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(description = "Producto y cantidad de una línea del pedido.")
public class OrderItemsRequest {
    @NotNull
    @Positive
    @Schema(description = "ID de un producto de la tienda.", example = "1")
    public Long productId;
    @NotNull
    @Positive
    @Max(1000)
    @Schema(description = "Cantidad solicitada, de 1 a 1000.", example = "2")
    public Integer quantity;
}
