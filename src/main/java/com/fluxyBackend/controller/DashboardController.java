package com.fluxyBackend.controller;

import com.fluxyBackend.exception.NotFoundException;

import com.fluxyBackend.DTOs.SalesPerDayResponse;
import com.fluxyBackend.DTOs.TopProductResponse;
import com.fluxyBackend.entity.Order;
import com.fluxyBackend.entity.OrderStatus;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.repository.OrderRepository;
import com.fluxyBackend.repository.UserRepository;
import com.fluxyBackend.service.OrderService;
import com.fluxyBackend.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final ProductService productService;
    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final UserRepository  userRepository;

    @GetMapping
    public Map<String, Object> dashboard(Authentication authentication) {
        String email = authentication.getName();

        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new NotFoundException("Usuario no encontrado"));

        List<Order> all = orderRepository.findByCompany(user.getCompany());

        long completed = all.stream().filter(o -> o.getStatus() == OrderStatus.COMPLETED).count();
        long pending   = all.stream().filter(o -> o.getStatus() == OrderStatus.PENDING).count();
        long cancelled = all.stream().filter(o -> o.getStatus() == OrderStatus.CANCELLED).count();

        double totalSales = all.stream()
                .filter(o -> o.getStatus() == OrderStatus.COMPLETED)
                .mapToDouble(Order::getTotal).sum();

        double avgTicket = completed > 0 ? totalSales / completed : 0;

        // Ventas esta semana
        LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);
        double salesThisWeek = all.stream()
                .filter(o -> o.getStatus() == OrderStatus.COMPLETED
                        && o.getCreatedAt() != null
                        && o.getCreatedAt().isAfter(weekAgo))
                .mapToDouble(Order::getTotal).sum();

        // Tasa de conversión
        double conversionRate = !all.isEmpty()
                ? Math.round((completed * 100.0 / all.size()) * 10) / 10.0
                : 0;

        Map<String, Object> data = new HashMap<>();
        data.put("totalProducts",   productService.conuntProducts(email));
        data.put("totalSales",      totalSales);
        data.put("ordersCount",     all.size());
        data.put("completedOrders", completed);
        data.put("pendingOrders",   pending);
        data.put("cancelledOrders", cancelled);
        data.put("averageTicket",   avgTicket);
        data.put("salesThisWeek",   salesThisWeek);
        data.put("conversionRate",  conversionRate);
        data.put("topProduct",      orderService.getTopProduct(email));
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