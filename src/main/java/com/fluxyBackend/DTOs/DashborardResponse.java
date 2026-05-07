package com.fluxyBackend.DTOs;

public class DashborardResponse {
    public double totalSales;
    public long   ordersCount;
    public long   completedOrders;
    public long   pendingOrders;
    public long   cancelledOrders;
    public double averageTicket;
    public double salesThisWeek;
    public double conversionRate;
    public String topProduct;
}