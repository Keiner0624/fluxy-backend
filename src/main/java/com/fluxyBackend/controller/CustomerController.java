package com.fluxyBackend.controller;

import com.fluxyBackend.DTOs.PageResponse;
import com.fluxyBackend.customer.activity.CustomerActivityService;
import com.fluxyBackend.security.access.AccessService;
import com.fluxyBackend.security.access.Member;
import com.fluxyBackend.security.access.Permission;
import com.fluxyBackend.security.access.RequirePermission;
import com.fluxyBackend.service.CustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "Clientes", description = "CRM ligero: compradores de la tienda, segmentos, actividad y pedidos.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;
    private final AccessService accessService;

    @Operation(summary = "Listar clientes",
            description = "Paginado. q busca por nombre, teléfono o correo; segment (NEW, FREQUENT, RECURRING, INACTIVE, "
                    + "HIGH_VALUE), tag, source, marketingAllowed y lastPurchaseFrom/To filtran; sort acepta "
                    + "lastOrderAt (por defecto), totalSpent, ordersCount, name o createdAt.")
    @GetMapping
    @RequirePermission(Permission.CUSTOMER_VIEW)
    public PageResponse<CustomerService.CustomerSummary> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String segment,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) Boolean marketingAllowed,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate lastPurchaseFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate lastPurchaseTo,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return customerService.list(accessService.current().companyId(),
                new CustomerService.Filters(q, tag, segment, source, marketingAllowed, lastPurchaseFrom, lastPurchaseTo),
                sort, direction, page, size);
    }

    @Operation(summary = "Etiquetas en uso", description = "Para el filtro del listado.")
    @GetMapping("/tags")
    @RequirePermission(Permission.CUSTOMER_VIEW)
    public List<String> tags() {
        return customerService.allTags(accessService.current().companyId());
    }

    @Operation(summary = "Perfil de un cliente",
            description = "Contacto, segmentos, origen, métricas, preferencias y últimos pedidos. "
                    + "Las notas internas solo vienen con el permiso CUSTOMER_NOTES.")
    @GetMapping("/{id}")
    @RequirePermission(Permission.CUSTOMER_VIEW)
    public CustomerService.CustomerDetail detail(@PathVariable Long id) {
        Member member = accessService.current();
        return customerService.detail(member.companyId(), id, member.can(Permission.CUSTOMER_NOTES));
    }

    @Operation(summary = "Segmentos automáticos de un cliente", description = "Con la regla que lo ubica en cada uno.")
    @GetMapping("/{id}/segments")
    @RequirePermission(Permission.CUSTOMER_VIEW)
    public List<CustomerService.Segment> segments(@PathVariable Long id) {
        return customerService.segments(accessService.current().companyId(), id);
    }

    @Operation(summary = "Actividad de un cliente", description = "Línea de tiempo paginada, de lo más reciente a lo más antiguo.")
    @GetMapping("/{id}/activity")
    @RequirePermission(Permission.CUSTOMER_VIEW)
    public PageResponse<CustomerActivityService.View> activity(@PathVariable Long id,
                                                               @RequestParam(defaultValue = "0") int page,
                                                               @RequestParam(defaultValue = "20") int size) {
        return customerService.activity(accessService.current().companyId(), id, page, size);
    }

    @Operation(summary = "Pedidos de un cliente", description = "Historial paginado.")
    @GetMapping("/{id}/orders")
    @RequirePermission({Permission.CUSTOMER_VIEW, Permission.ORDER_VIEW})
    public PageResponse<CustomerService.CustomerOrder> orders(@PathVariable Long id,
                                                             @RequestParam(defaultValue = "0") int page,
                                                             @RequestParam(defaultValue = "10") int size) {
        return customerService.orders(accessService.current().companyId(), id, page, size);
    }

    @Operation(summary = "Crear un cliente", description = "Los clientes también se crean solos con cada pedido.")
    @PostMapping
    @RequirePermission(Permission.CUSTOMER_CREATE)
    public CustomerService.CustomerDetail create(@RequestBody CustomerService.CustomerRequest request) {
        return customerService.create(accessService.current(), request);
    }

    @Operation(summary = "Actualizar un cliente",
            description = "Actualiza solo los campos enviados. Cambiar notas pide CUSTOMER_NOTES y etiquetas, CUSTOMER_TAGS.")
    @PutMapping("/{id}")
    @RequirePermission(Permission.CUSTOMER_UPDATE)
    public CustomerService.CustomerDetail update(@PathVariable Long id,
                                                 @RequestBody CustomerService.CustomerRequest request) {
        return customerService.update(accessService.current(), id, request);
    }
}
