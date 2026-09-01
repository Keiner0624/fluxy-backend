package com.fluxyBackend.DTOs;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public class CreateOrderRequest {
    @NotBlank
    @Size(max = 150)
    public String customerName;
    @Size(max = 30)
    public String customerPhone;
    @Size(max = 300)
    public String customerAddress;
    @NotEmpty
    @Valid
    public List<@NotNull OrderItemsRequest> items;
    @Size(max = 50)
    public String couponCode;
}
