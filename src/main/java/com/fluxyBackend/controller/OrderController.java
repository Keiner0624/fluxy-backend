package com.fluxyBackend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import com.fluxyBackend.DTOs.CreateOrderRequest;
import com.fluxyBackend.DTOs.DashborardResponse;
import com.fluxyBackend.DTOs.SalesPerDayResponse;
import com.fluxyBackend.DTOs.TopProductResponse;
import com.fluxyBackend.entity.Order;
import com.fluxyBackend.response.OrderRespose;
import com.fluxyBackend.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Pedidos", description = "Pedidos y estadísticas de la empresa del usuario autenticado.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService            orderService;

    @Operation(summary = "Crear un pedido",
            description = "Calcula el total usando los precios del catálogo y valida las cantidades.")
    @PostMapping
    public Order createOrder(@Valid @RequestBody CreateOrderRequest request, Authentication authentication) {
        return orderService.createOrder(request, authentication.getName());
    }

    @Operation(summary = "Listar mis pedidos",
            description = "Devuelve los pedidos de la empresa del usuario.")
    @GetMapping
    public List<Order> getOrders(Authentication authentication) {
        return orderService.getOrder(authentication.getName());
    }

    @Operation(summary = "Consultar un pedido",
            description = "Busca un pedido de la empresa del usuario por su identificador.")
    @GetMapping("/{id}")
    public Order getOrderById(@PathVariable Long id, Authentication authentication) {
        return orderService.getOrderById(id, authentication.getName());
    }

    @Operation(summary = "Cancelar un pedido",
            description = "Cambia el estado del pedido según las reglas de cancelación del servicio.")
    @PutMapping("/{id}/cancel")
    public Order cancelOrder(@PathVariable Long id, Authentication authentication) {
        return orderService.cancelOrder(id, authentication.getName());
    }

    @Operation(summary = "Consultar el total de ventas",
            description = "Devuelve el importe acumulado de las ventas completadas.")
    @GetMapping("/total-sales")
    public double totalSales(Authentication authentication) {
        return orderService.getTotalSales(authentication.getName());
    }

    @Operation(summary = "Completar un pedido",
            description = "Marca el pedido como completado y devuelve su resumen.")
    @PutMapping("/{id}/complete")
    public OrderRespose completeOrder(@PathVariable Long id, Authentication authentication) {
        return orderService.completeOrder(id, authentication.getName());
    }

    @Operation(summary = "Contar pedidos",
            description = "La ruta conserva la grafía existente: /orders/dashborard/orders-count.")
    @GetMapping("/dashborard/orders-count")
    public long ordersCount(Authentication authentication) {
        return orderService.getOrdersCount(authentication.getName());
    }

    @Operation(summary = "Consultar el producto más vendido",
            description = "Devuelve el nombre del producto más vendido.")
    @GetMapping("/dashboard/top-product")
    public String topProduct(Authentication authentication) {
        return orderService.getTopProduct(authentication.getName());
    }

    @Operation(summary = "Consultar el resumen de pedidos",
            description = "Devuelve los indicadores de pedidos y ventas.")
    @GetMapping("/dashboard")
    public DashborardResponse dashborard(Authentication authentication) {
        return orderService.getDashborard(authentication.getName());
    }

    @Operation(summary = "Consultar ventas por día",
            description = "Devuelve la serie diaria de ventas.")
    @GetMapping("/dashboard/sales-per-day")
    public List<SalesPerDayResponse> salesDay(Authentication authentication) {
        return orderService.getSalesPerDay(authentication.getName());
    }

    @Operation(summary = "Consultar los productos más vendidos",
            description = "Permite seleccionar el período con el parámetro period.")
    @GetMapping("/dashboard/top-products")
    public List<TopProductResponse> getTopProducts(
            @Parameter(description = "today para el día actual; month (por defecto) o cualquier otro valor para el último mes.", example = "month")
            @RequestParam(defaultValue = "month") String period,
            Authentication authentication) {
        return orderService.getTopProducts(authentication.getName(), period);
    }
}
