package com.fluxyBackend.controller;

import com.fluxyBackend.DTOs.SalesPerDayResponse;
import com.fluxyBackend.DTOs.TopProductResponse;
import com.fluxyBackend.service.OrderService;
import com.fluxyBackend.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class DashboardController {
    private final ProductService productService;
    private final OrderService orderService;

    @GetMapping
    public Map<String, Object> dashboard(Authentication authentication) {
        String email = authentication.getName();
        Map<String, Object> data = new HashMap<>();
        data.put("totalProducts", productService.conuntProducts(email));
        data.put("totalSales",    orderService.getTotalSales(email));
        data.put("ordersCount",   orderService.getOrdersCount(email));
        data.put("topProduct",    orderService.getTopProduct(email));
        return data;
    }

    @GetMapping("/sales-per-day")
    public List<SalesPerDayResponse> salesPerDay(Authentication authentication) {
        return orderService.getSalesPerDay(authentication.getName());
    }

    @GetMapping("/top-products")
    public List<TopProductResponse> topProducts(
            Authentication authentication,
            @RequestParam(defaultValue = "month") String period) {
        return orderService.getTopProducts(authentication.getName(), period);
    }
}
