package com.fluxyBackend.DTOs;

import java.util.List;

public class CreateOrderRequest {
    public String customerName;
    public List<OrderItemsRequest> items;
}
