package com.fluxyBackend.controller;

import com.fluxyBackend.DTOs.CreateOrderRequest;
import com.fluxyBackend.DTOs.DashborardResponse;
import com.fluxyBackend.DTOs.SalesPerDayResponse;
import com.fluxyBackend.DTOs.TopProductResponse;
import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Order;
import com.fluxyBackend.repository.OrderRepository;
import com.fluxyBackend.response.OrderRespose;
import com.fluxyBackend.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {
    private final OrderService orderService;

    @PostMapping
    public Order createOrder(@RequestBody CreateOrderRequest request, Authentication authentication) {
        return orderService.createOrder(request, authentication.getName());
    }

    @GetMapping
    public List<Order> getOrders(Authentication authentication) {
        return orderService.getOrder(authentication.getName());
    }

    @GetMapping("/{id}")
    public Order getOrderById(@PathVariable Long id, Authentication authentication) {
        return orderService.getOrderById(id, authentication.getName());
    }

    @PutMapping("/{id}/cancel")
    public Order cancelOrder(@PathVariable Long id, Authentication authentication) {
        return orderService.cancelOrder(id, authentication.getName());
    }

    @GetMapping("/total-sales")
    public double totalSales(Authentication authentication){
        return orderService.getTotalSales(authentication.getName());
    }

    @PutMapping("/{id}/complete")
    public OrderRespose completeOrder(@PathVariable Long id, Authentication authentication) {
        return orderService.completeOrder(id, authentication.getName());
    }
    @GetMapping("/dashborard/orders-count")
    public long ordersCount(Authentication authentication){
        return orderService.getOrdersCount(authentication.getName());
    }

    @GetMapping("/dashboard/top-product")
    public String topProduct(Authentication authentication){
        return  orderService.getTopProduct(authentication.getName());
    }

    @GetMapping("/dashboard")
    public DashborardResponse dashborard(Authentication authentication){
        return orderService.getDashborard(authentication.getName());
    }

    @GetMapping("/dashboard/sales-per-day")
    public List<SalesPerDayResponse> salesDay(Authentication authentication){
        return orderService.getSalesPerDay(authentication.getName());
    }
    @GetMapping("/dashboard/top-products")
    public List<TopProductResponse> getTopProducts(@RequestParam(defaultValue = "month") String period,
                                                   Authentication authentication) {
        return orderService.getTopProducts(authentication.getName(), period);
    }
}
