package com.fluxyBackend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import com.fluxyBackend.DTOs.CreateOrderRequest;
import com.fluxyBackend.DTOs.DashborardResponse;
import com.fluxyBackend.DTOs.PageResponse;
import com.fluxyBackend.DTOs.SalesPerDayResponse;
import com.fluxyBackend.DTOs.TopProductResponse;
import com.fluxyBackend.entity.Order;
import com.fluxyBackend.response.OrderRespose;
import com.fluxyBackend.security.access.AccessService;
import com.fluxyBackend.security.access.Permission;
import com.fluxyBackend.security.access.RequirePermission;
import com.fluxyBackend.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Tag(name = "Pedidos", description = "Pedidos y estadísticas de la empresa del usuario autenticado.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService            orderService;
    private final AccessService           accessService;
    private final com.fluxyBackend.service.AuditService auditService;

    @Operation(summary = "Crear un pedido",
            description = "Calcula el total usando los precios del catálogo y valida las cantidades.")
    @PostMapping
    @RequirePermission(Permission.ORDER_UPDATE)
    public Order createOrder(@Valid @RequestBody CreateOrderRequest request) {
        com.fluxyBackend.security.access.Member member = accessService.current();
        Order order = orderService.createOrder(request, member);
        auditService.record(member, com.fluxyBackend.service.AuditAction.ORDER_CREATED, "ORDER", order.getId(), Map.of("total", order.getTotal()));
        return order;
    }

    @Operation(summary = "Listar mis pedidos",
            description = "Devuelve todos los pedidos de la empresa. Para buscar y paginar usá /orders/search.")
    @GetMapping
    @RequirePermission(Permission.ORDER_VIEW)
    public List<Order> getOrders(Authentication authentication) {
        return orderService.getOrder(authentication.getName());
    }

    @Operation(summary = "Buscar pedidos",
            description = "Paginado y del más reciente al más antiguo. status acepta un estado, IN_PROGRESS o ALL; "
                    + "q busca por número de pedido, cliente o teléfono; from y to son días del negocio (YYYY-MM-DD).")
    @GetMapping("/search")
    @RequirePermission(Permission.ORDER_VIEW)
    public PageResponse<OrderService.OrderRow> search(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String paymentMethod,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return orderService.search(accessService.current().companyId(), status, q, from, to, paymentMethod, page, size);
    }

    @Operation(summary = "Contar pedidos por estado", description = "Para las pestañas del listado.")
    @GetMapping("/status-counts")
    @RequirePermission(Permission.ORDER_VIEW)
    public Map<String, Long> statusCounts() {
        return orderService.statusCounts(accessService.current().companyId());
    }

    @Operation(summary = "Detalle de un pedido",
            description = "Incluye productos, historial de estados, pagos y los estados a los que puede pasar.")
    @GetMapping("/{id}/detail")
    @RequirePermission(Permission.ORDER_VIEW)
    public OrderService.OrderDetail detail(@PathVariable Long id) {
        return orderService.detail(accessService.current().companyId(), id);
    }

    @Operation(summary = "Cambiar el estado de un pedido",
            description = "PENDING → CONFIRMED → PREPARING → READY → SHIPPED → DELIVERED; se puede saltar etapas pero no volver. "
                    + "CANCELLED exige note con el motivo y el permiso ORDER_CANCEL, y devuelve el stock.")
    @PatchMapping("/{id}/status")
    @RequirePermission(value = {Permission.ORDER_UPDATE, Permission.ORDER_CANCEL}, any = true)
    public OrderService.OrderDetail changeStatus(@PathVariable Long id, @RequestBody OrderService.StatusRequest request) {
        com.fluxyBackend.security.access.Member member = accessService.current();
        OrderService.OrderDetail detail = orderService.changeStatus(member, id, request);
        if (request.status() != null && "CANCELLED".equalsIgnoreCase(request.status())) {
            auditService.record(member, com.fluxyBackend.service.AuditAction.ORDER_CANCELLED, "ORDER", id, null);
        }
        return detail;
    }

    @Operation(summary = "Consultar un pedido",
            description = "Busca un pedido de la empresa del usuario por su identificador.")
    @GetMapping("/{id}")
    @RequirePermission(Permission.ORDER_VIEW)
    public Order getOrderById(@PathVariable Long id, Authentication authentication) {
        return orderService.getOrderById(id, authentication.getName());
    }

    @Operation(summary = "Cancelar un pedido",
            description = "Equivale a cambiar el estado a CANCELLED con un motivo genérico.")
    @PutMapping("/{id}/cancel")
    @RequirePermission(Permission.ORDER_CANCEL)
    public Order cancelOrder(@PathVariable Long id) {
        com.fluxyBackend.security.access.Member member = accessService.current();
        Order order = orderService.cancelOrder(id, member);
        auditService.record(member, com.fluxyBackend.service.AuditAction.ORDER_CANCELLED, "ORDER", id, null);
        return order;
    }

    @Operation(summary = "Consultar el total de ventas",
            description = "Devuelve el importe acumulado de las ventas (pedidos confirmados en adelante).")
    @GetMapping("/total-sales")
    @RequirePermission(Permission.REPORT_VIEW)
    public double totalSales(Authentication authentication) {
        return orderService.getTotalSales(authentication.getName());
    }

    @Operation(summary = "Completar un pedido",
            description = "Equivale a cambiar el estado a DELIVERED.")
    @PutMapping("/{id}/complete")
    @RequirePermission(Permission.ORDER_UPDATE)
    public OrderRespose completeOrder(@PathVariable Long id) {
        return orderService.completeOrder(id, accessService.current());
    }

    @Operation(summary = "Contar pedidos",
            description = "La ruta conserva la grafía existente: /orders/dashborard/orders-count.")
    @GetMapping("/dashborard/orders-count")
    @RequirePermission(Permission.ORDER_VIEW)
    public long ordersCount(Authentication authentication) {
        return orderService.getOrdersCount(authentication.getName());
    }

    @Operation(summary = "Consultar el producto más vendido",
            description = "Devuelve el nombre del producto más vendido.")
    @GetMapping("/dashboard/top-product")
    @RequirePermission(Permission.REPORT_VIEW)
    public String topProduct(Authentication authentication) {
        return orderService.getTopProduct(authentication.getName());
    }

    @Operation(summary = "Consultar el resumen de pedidos",
            description = "Devuelve los indicadores de pedidos y ventas.")
    @GetMapping("/dashboard")
    @RequirePermission(Permission.REPORT_VIEW)
    public DashborardResponse dashborard(Authentication authentication) {
        return orderService.getDashborard(authentication.getName());
    }

    @Operation(summary = "Consultar ventas por día",
            description = "Devuelve la serie diaria de ventas.")
    @GetMapping("/dashboard/sales-per-day")
    @RequirePermission(Permission.REPORT_VIEW)
    public List<SalesPerDayResponse> salesDay(Authentication authentication) {
        return orderService.getSalesPerDay(authentication.getName());
    }

    @Operation(summary = "Consultar los productos más vendidos",
            description = "Permite seleccionar el período con el parámetro period.")
    @GetMapping("/dashboard/top-products")
    @RequirePermission(Permission.REPORT_VIEW)
    public List<TopProductResponse> getTopProducts(
            @Parameter(description = "today para el día actual; month (por defecto) o cualquier otro valor para el último mes.", example = "month")
            @RequestParam(defaultValue = "month") String period,
            Authentication authentication) {
        return orderService.getTopProducts(authentication.getName(), period);
    }
}
