package com.fluxyBackend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import com.fluxyBackend.DTOs.PageResponse;
import com.fluxyBackend.entity.Prodcut;
import com.fluxyBackend.security.access.AccessService;
import com.fluxyBackend.security.access.Permission;
import com.fluxyBackend.security.access.RequirePermission;
import com.fluxyBackend.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Productos", description = "Catálogo del vendedor autenticado.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductController {
    private final ProductService prodcutService;
    private final AccessService accessService;

    @Operation(summary = "Crear un producto",
            description = "Asocia el producto a la empresa del usuario. Responde 403 cuando se alcanza el límite del plan.")
    @PostMapping
    @RequirePermission(Permission.PRODUCT_CREATE)
    public Prodcut create(@Valid @RequestBody Prodcut prodcut) {
        return prodcutService.createProduct(prodcut, accessService.current());
    }

    @Operation(summary = "Listar mis productos",
            description = "Devuelve el catálogo completo de la empresa del usuario. Para catálogos grandes usá /products/search.")
    @GetMapping
    @RequirePermission(Permission.PRODUCT_VIEW)
    public List<Prodcut> getAll(Authentication authentication) {
        return prodcutService.getAll(authentication.getName());
    }

    @Operation(summary = "Buscar productos",
            description = "Paginado. category acepta un id o none; status ACTIVE o HIDDEN; stock in, low u out; "
                    + "sort name, price, stock o createdAt.")
    @GetMapping("/search")
    @RequirePermission(Permission.PRODUCT_VIEW)
    public PageResponse<Prodcut> search(@RequestParam(required = false) String q,
                                        @RequestParam(required = false) String category,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(required = false) String stock,
                                        @RequestParam(required = false) Double minPrice,
                                        @RequestParam(required = false) Double maxPrice,
                                        @RequestParam(required = false) String sort,
                                        @RequestParam(required = false) String direction,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return prodcutService.search(accessService.current().companyId(), q, category, status, stock,
                minPrice, maxPrice, sort, direction, page, size);
    }

    @Operation(summary = "Indicadores del catálogo",
            description = "Productos activos y ocultos, stock bajo, agotados y valor del inventario.")
    @GetMapping("/stats")
    @RequirePermission(Permission.PRODUCT_VIEW)
    public ProductService.Stats stats() {
        return prodcutService.stats(accessService.current().companyId());
    }

    @Operation(summary = "Actualizar un producto",
            description = "Edición completa. Ignora stock: se cambia con movimientos de inventario.")
    @PutMapping("/{id}")
    @RequirePermission(Permission.PRODUCT_UPDATE)
    public Prodcut update(@PathVariable Long id, @Valid @RequestBody Prodcut prodcut) {
        return prodcutService.update(id, prodcut, accessService.current());
    }

    @Operation(summary = "Edición rápida",
            description = "Modifica solo los campos enviados: name, price, status, categoryId, clearCategory, sku, minStock, cost.")
    @PatchMapping("/{id}")
    @RequirePermission(Permission.PRODUCT_UPDATE)
    public Prodcut quickEdit(@PathVariable Long id, @RequestBody ProductService.QuickEditRequest request) {
        return prodcutService.quickEdit(id, request, accessService.current());
    }

    @Operation(summary = "Acciones masivas",
            description = "action: ACTIVATE, HIDE, SET_CATEGORY (categoryId, null quita la categoría) o DELETE. "
                    + "DELETE oculta en lugar de borrar los productos que tienen pedidos.")
    @PostMapping("/bulk")
    @RequirePermission(value = {Permission.PRODUCT_UPDATE, Permission.PRODUCT_DELETE}, any = true)
    public ProductService.BulkResult bulk(@RequestBody ProductService.BulkRequest request) {
        return prodcutService.bulk(request, accessService.current(), accessService);
    }

    @Operation(summary = "Eliminar un producto",
            description = "Responde 409 si el producto tiene pedidos: en ese caso se oculta en vez de borrarse.")
    @DeleteMapping("/{id}")
    @RequirePermission(Permission.PRODUCT_DELETE)
    public void delete(@PathVariable Long id) {
        prodcutService.delete(id, accessService.current());
    }
}
