package com.fluxyBackend.DTOs;

import io.swagger.v3.oas.annotations.media.Schema;

import com.fluxyBackend.entity.Coupon.DiscountType;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

@Schema(description = "Cupón de descuento de la empresa autenticada.")
public class CreateCouponRequest {
    @NotBlank
    @Size(max = 50)
    @Schema(description = "Código único en la tienda; se guarda en mayúsculas.", example = "PROMO10")
    public String code;

    @NotNull
    @Schema(description = "Tipo de descuento.", example = "PERCENTAGE")
    public DiscountType discountType = DiscountType.PERCENTAGE;

    @NotNull
    @Positive
    @Schema(description = "Porcentaje (hasta 100) o importe fijo según discountType.", example = "10")
    public Double discountValue;

    @Positive
    @Schema(description = "Máximo de usos; omitir para no fijar un límite.", example = "100")
    public Integer usageLimit;

    @PositiveOrZero
    @Schema(description = "Importe mínimo del pedido para aplicar el cupón.", example = "50")
    public Double minOrderAmount;

    @Future
    @Schema(description = "Vencimiento opcional, con fecha y hora local futura.", example = "2030-12-31T23:59:59")
    public LocalDateTime expiresAt;
}
