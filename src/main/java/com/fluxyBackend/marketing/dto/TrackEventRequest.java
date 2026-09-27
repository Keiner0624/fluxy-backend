package com.fluxyBackend.marketing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Evento de la tienda pública. No lleva datos personales: solo el código y un id aleatorio del navegador. */
@Schema(description = "Paso del embudo informado por la tienda pública.")
public record TrackEventRequest(
        @NotBlank @Schema(description = "VIEW, PRODUCT_VIEW, ADD_TO_CART o CHECKOUT_STARTED", example = "VIEW") String type,
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9]{8,24}$", message = "código de campaña inválido")
        @Schema(description = "trackingCode del enlace (parámetro cmp)", example = "q7Rk2mX9aP4d") String code,
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{16,64}$", message = "sesión inválida")
        @Schema(description = "Identificador aleatorio del navegador") String sessionId,
        @Schema(description = "Producto, en PRODUCT_VIEW y ADD_TO_CART") Long productId,
        @Size(max = 40) @Schema(description = "utm_source del enlace", example = "whatsapp") String source) {
}
