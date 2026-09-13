package com.fluxyBackend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import com.fluxyBackend.exception.NotFoundException;

import com.fluxyBackend.DTOs.SalesPerDayResponse;
import com.fluxyBackend.DTOs.TopProductResponse;
import com.fluxyBackend.entity.Order;
import com.fluxyBackend.entity.OrderStatus;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.repository.OrderRepository;
import com.fluxyBackend.repository.UserRepository;
import com.fluxyBackend.security.access.AccessService;
import com.fluxyBackend.security.access.Permission;
import com.fluxyBackend.security.access.RequirePermission;
import com.fluxyBackend.service.AnalyticsService;
import com.fluxyBackend.service.OrderService;
import com.fluxyBackend.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "Dashboard", description = "Indicadores de la empresa del usuario autenticado.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final ProductService productService;
    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final UserRepository  userRepository;
    private final AnalyticsService analyticsService;
    private final AccessService accessService;

    @Operation(summary = "Resumen del negocio",
            description = "Ventas de hoy y del mes contra el período anterior, pedidos pendientes, ticket promedio, "
                    + "clientes nuevos, stock bajo y últimos pedidos. Los días se cuentan en la zona horaria del negocio.")
    @GetMapping("/overview")
    @RequirePermission(Permission.ORDER_VIEW)
    public AnalyticsService.Overview overview() {
        return analyticsService.overview(accessService.current().companyId());
    }

    @Operation(summary = "Métricas de un período",
            description = "Por defecto los últimos 30 días. Incluye serie diaria, pedidos por estado, conversión, "
                    + "productos más vendidos y clientes recurrentes.")
    @GetMapping("/metrics")
    @RequirePermission(Permission.REPORT_VIEW)
    public AnalyticsService.Metrics metrics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return analyticsService.metrics(accessService.current().companyId(), from, to);
    }

    @Operation(summary = "Consultar los indicadores de mi tienda",
            description = "Incluye productos, ventas, pedidos por estado, ticket promedio, ventas de la semana, conversión y producto más vendido.")
    @GetMapping
    @RequirePermission(Permission.REPORT_VIEW)
    public Map<String, Object> dashboard(Authentication authentication) {
        String email = authentication.getName();

        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new NotFoundException("Usuario no encontrado"));

        List<Order> all = orderRepository.findByCompany(user.getCompany());

        long completed = all.stream().filter(o -> o.getStatus() != null && o.getStatus().isSale()).count();
        long pending   = all.stream().filter(o -> o.getStatus() == OrderStatus.PENDING).count();
        long cancelled = all.stream().filter(o -> o.getStatus() == OrderStatus.CANCELLED).count();

        double totalSales = all.stream()
                .filter(o -> o.getStatus() != null && o.getStatus().isSale())
                .mapToDouble(Order::getTotal).sum();

        double avgTicket = completed > 0 ? totalSales / completed : 0;

        // Ventas esta semana
        LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);
        double salesThisWeek = all.stream()
                .filter(o -> o.getStatus() != null && o.getStatus().isSale()
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

    @Operation(summary = "Consultar ventas diarias",
            description = "Devuelve la serie diaria de ventas de la empresa.")
    @GetMapping("/sales-per-day")
    @RequirePermission(Permission.REPORT_VIEW)
    public List<SalesPerDayResponse> salesPerDay(Authentication authentication) {
        return orderService.getSalesPerDay(authentication.getName());
    }

    @Operation(summary = "Consultar el ranking de productos",
            description = "Permite seleccionar el período con el parámetro period.")
    @GetMapping("/top-products")
    @RequirePermission(Permission.REPORT_VIEW)
    public List<TopProductResponse> topProducts(
            Authentication authentication,
            @Parameter(description = "today para el día actual; month (por defecto) o cualquier otro valor para el último mes.", example = "month")
            @RequestParam(defaultValue = "month") String period) {
        return orderService.getTopProducts(authentication.getName(), period);
    }
}
