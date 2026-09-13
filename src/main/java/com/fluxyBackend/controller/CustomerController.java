package com.fluxyBackend.controller;

import com.fluxyBackend.DTOs.PageResponse;
import com.fluxyBackend.security.access.AccessService;
import com.fluxyBackend.security.access.Permission;
import com.fluxyBackend.security.access.RequirePermission;
import com.fluxyBackend.service.CustomerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Clientes", description = "Compradores de la tienda, conectados a sus pedidos.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;
    private final AccessService accessService;

    @Operation(summary = "Listar clientes",
            description = "Paginado. q busca por nombre, teléfono o correo; sort acepta lastOrderAt (por defecto), "
                    + "totalSpent, ordersCount, name o createdAt.")
    @GetMapping
    @RequirePermission(Permission.CUSTOMER_VIEW)
    public PageResponse<CustomerService.CustomerSummary> list(@RequestParam(required = false) String q,
                                                              @RequestParam(required = false) String tag,
                                                              @RequestParam(required = false) String sort,
                                                              @RequestParam(required = false) String direction,
                                                              @RequestParam(defaultValue = "0") int page,
                                                              @RequestParam(defaultValue = "20") int size) {
        return customerService.list(accessService.current().companyId(), q, tag, sort, direction, page, size);
    }

    @Operation(summary = "Etiquetas en uso", description = "Para el filtro del listado.")
    @GetMapping("/tags")
    @RequirePermission(Permission.CUSTOMER_VIEW)
    public List<String> tags() {
        return customerService.allTags(accessService.current().companyId());
    }

    @Operation(summary = "Detalle de un cliente", description = "Contacto, notas, etiquetas, totales y últimos pedidos.")
    @GetMapping("/{id}")
    @RequirePermission(Permission.CUSTOMER_VIEW)
    public CustomerService.CustomerDetail detail(@PathVariable Long id) {
        return customerService.detail(accessService.current().companyId(), id);
    }

    @Operation(summary = "Crear un cliente", description = "Los clientes también se crean solos con cada pedido.")
    @PostMapping
    @RequirePermission(Permission.CUSTOMER_UPDATE)
    public CustomerService.CustomerDetail create(@RequestBody CustomerService.CustomerRequest request) {
        return customerService.create(accessService.current().companyId(), request);
    }

    @Operation(summary = "Actualizar un cliente", description = "Actualiza solo los campos enviados.")
    @PutMapping("/{id}")
    @RequirePermission(Permission.CUSTOMER_UPDATE)
    public CustomerService.CustomerDetail update(@PathVariable Long id,
                                                 @RequestBody CustomerService.CustomerRequest request) {
        return customerService.update(accessService.current().companyId(), id, request);
    }
}
