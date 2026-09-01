package com.fluxyBackend.DTOs;

import com.fluxyBackend.entity.Coupon.DiscountType;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public class CreateCouponRequest {
    @NotBlank
    @Size(max = 50)
    public String code;

    @NotNull
    public DiscountType discountType = DiscountType.PERCENTAGE;

    @NotNull
    @Positive
    public Double discountValue;

    @Positive
    public Integer usageLimit;

    @PositiveOrZero
    public Double minOrderAmount;

    @Future
    public LocalDateTime expiresAt;
}
