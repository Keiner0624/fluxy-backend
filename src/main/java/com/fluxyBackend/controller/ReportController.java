package com.fluxyBackend.controller;

import com.fluxyBackend.security.access.AccessService;
import com.fluxyBackend.security.access.Permission;
import com.fluxyBackend.security.access.RequirePermission;
import com.fluxyBackend.service.AnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** Reportes por período. from y to son días del negocio; por defecto los últimos 30 días. */
@Tag(name = "Reportes", description = "Informes por período y datos para exportar.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/reports")
@RequiredArgsConstructor
public class ReportController {

    private final AnalyticsService analyticsService;
    private final AccessService accessService;

    @Operation(summary = "Resumen del período", description = "Incluye la comparación con el período anterior de igual duración.")
    @GetMapping("/summary")
    @RequirePermission(Permission.REPORT_VIEW)
    public AnalyticsService.Summary summary(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return analyticsService.reportSummary(companyId(), from, to);
    }

    @Operation(summary = "Ventas por período", description = "groupBy: day, week o month. Los períodos sin ventas vienen en cero.")
    @GetMapping("/sales")
    @RequirePermission(Permission.REPORT_VIEW)
    public List<AnalyticsService.SeriesPoint> sales(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                    @RequestParam(defaultValue = "day") String groupBy) {
        return analyticsService.salesSeries(companyId(), from, to, groupBy);
    }

    @Operation(summary = "Ventas por categoría")
    @GetMapping("/categories")
    @RequirePermission(Permission.REPORT_VIEW)
    public List<AnalyticsService.CategorySales> categories(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return analyticsService.salesByCategory(companyId(), from, to);
    }

    @Operation(summary = "Productos más vendidos")
    @GetMapping("/top-products")
    @RequirePermission(Permission.REPORT_VIEW)
    public List<AnalyticsService.TopProduct> topProducts(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                         @RequestParam(defaultValue = "10") int limit) {
        return analyticsService.topProducts(companyId(), from, to, limit);
    }

    @Operation(summary = "Clientes frecuentes")
    @GetMapping("/top-customers")
    @RequirePermission(Permission.REPORT_VIEW)
    public List<AnalyticsService.TopCustomer> topCustomers(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                           @RequestParam(defaultValue = "10") int limit) {
        return analyticsService.topCustomers(companyId(), from, to, limit);
    }

    @Operation(summary = "Pedidos cancelados", description = "Con el motivo de cada cancelación.")
    @GetMapping("/cancelled")
    @RequirePermission(Permission.REPORT_VIEW)
    public List<AnalyticsService.CancelledOrder> cancelled(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return analyticsService.cancelledOrders(companyId(), from, to);
    }

    @Operation(summary = "Datos para exportar",
            description = "type: sales, categories, products, customers o cancelled. Requiere REPORT_EXPORT; "
                    + "el panel arma el CSV, Excel o PDF con estas columnas y filas.")
    @GetMapping("/export")
    @RequirePermission(Permission.REPORT_EXPORT)
    public AnalyticsService.Export export(@RequestParam String type,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return analyticsService.export(companyId(), type, from, to);
    }

    private Long companyId() {
        return accessService.current().companyId();
    }
}
