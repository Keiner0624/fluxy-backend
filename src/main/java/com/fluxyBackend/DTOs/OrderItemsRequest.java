package com.fluxyBackend.DTOs;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public class OrderItemsRequest {
    @NotNull
    @Positive
    public Long productId;
    @NotNull
    @Positive
    @Max(1000)
    public Integer quantity;
}
