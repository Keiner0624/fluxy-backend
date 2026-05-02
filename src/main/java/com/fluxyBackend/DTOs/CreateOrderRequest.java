package com.fluxyBackend.DTOs;

import java.util.List;

public class CreateOrderRequest {
    public String customerName;
    public String customerPhone;
    public String customerAddress;
    public List<OrderItemsRequest> items;
}
