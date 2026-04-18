package com.fluxyBackend.response;

import org.apache.catalina.mbeans.SparseUserDatabaseMBean;

import java.time.LocalDateTime;
import java.util.List;

public class OrderRespose {
    public Long id;
    public String customerName;
    public double total;
    public LocalDateTime createdAt;
    public String status;
    public List<OrderItemResponse> items;
}
