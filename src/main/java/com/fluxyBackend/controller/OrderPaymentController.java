package com.fluxyBackend.controller;

import com.fluxyBackend.DTOs.PageResponse;
import com.fluxyBackend.security.access.AccessService;
import com.fluxyBackend.security.access.Permission;
import com.fluxyBackend.security.access.RequirePermission;
import com.fluxyBackend.service.OrderPaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/** Cobros de pedidos. Comparte /payments con MercadoPagoController, que cobra los planes. */
@Tag(name = "Cobros de pedidos", description = "Pagos de los pedidos de la tienda y su conciliación.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class OrderPaymentController {

    private final OrderPaymentService paymentService;
    private final AccessService accessService;

    @Operation(summary = "Listar cobros",
            description = "Paginado. status PENDING, APPROVED, REJECTED o REFUNDED; q busca por número de pedido o referencia.")
    @GetMapping
    @RequirePermission(Permission.PAYMENT_VIEW)
    public PageResponse<OrderPaymentService.PaymentView> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return paymentService.list(accessService.current().companyId(), status, method, q, from, to, page, size);
    }

    @Operation(summary = "Totales por estado", description = "Aprobado, pendiente, rechazado, reembolsado y neto cobrado.")
    @GetMapping("/summary")
    @RequirePermission(Permission.PAYMENT_VIEW)
    public OrderPaymentService.Summary summary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return paymentService.summary(accessService.current().companyId(), from, to);
    }

    @Operation(summary = "Conciliación",
            description = "Ventas sin cobrar por completo, pedidos cancelados con dinero a devolver y cobros de más.")
    @GetMapping("/reconciliation")
    @RequirePermission(Permission.PAYMENT_VIEW)
    public OrderPaymentService.Reconciliation reconciliation() {
        return paymentService.reconciliation(accessService.current().companyId());
    }

    @Operation(summary = "Registrar un cobro",
            description = "status APPROVED (por defecto) o PENDING. No puede superar lo que falta cobrar del pedido.")
    @PostMapping
    @RequirePermission(Permission.PAYMENT_UPDATE)
    public OrderPaymentService.PaymentView register(@RequestBody OrderPaymentService.RegisterRequest request) {
        return paymentService.register(accessService.current(), request);
    }

    @Operation(summary = "Actualizar un cobro",
            description = "Un cobro pendiente se aprueba o rechaza; en cualquier estado se corrigen medio, referencia y nota.")
    @PatchMapping("/{id}")
    @RequirePermission(Permission.PAYMENT_UPDATE)
    public OrderPaymentService.PaymentView update(@PathVariable Long id,
                                                  @RequestBody OrderPaymentService.UpdateRequest request) {
        return paymentService.update(accessService.current(), id, request);
    }

    @Operation(summary = "Reembolsar un cobro",
            description = "Total o parcial, con motivo. Sin amount reembolsa lo que queda del cobro.")
    @PostMapping("/{id}/refund")
    @RequirePermission(Permission.PAYMENT_REFUND)
    public OrderPaymentService.PaymentView refund(@PathVariable Long id,
                                                  @RequestBody OrderPaymentService.RefundRequest request) {
        return paymentService.refund(accessService.current(), id, request);
    }
}
