package com.fluxyBackend.controller;

import com.fluxyBackend.DTOs.PageResponse;
import com.fluxyBackend.security.access.AccessService;
import com.fluxyBackend.security.access.Member;
import com.fluxyBackend.security.access.Permission;
import com.fluxyBackend.security.access.RequirePermission;
import com.fluxyBackend.service.InventoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Inventario", description = "Stock, alertas y movimientos.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;
    private final AccessService accessService;
    private final com.fluxyBackend.service.AuditService auditService;

    public record MinStockRequest(Integer minStock) {}

    @Operation(summary = "Resumen del inventario", description = "Productos, stock bajo, agotados y valor del stock.")
    @GetMapping("/summary")
    @RequirePermission(Permission.INVENTORY_VIEW)
    public InventoryService.Summary summary() {
        return inventoryService.summary(accessService.current().companyId());
    }

    @Operation(summary = "Stock por producto",
            description = "Paginado, de menor a mayor stock. filter: all, attention (bajo o agotado), low, out u ok.")
    @GetMapping("/stock")
    @RequirePermission(Permission.INVENTORY_VIEW)
    public PageResponse<InventoryService.StockItem> stock(@RequestParam(required = false) String q,
                                                          @RequestParam(defaultValue = "all") String filter,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "20") int size) {
        return inventoryService.stock(accessService.current().companyId(), q, filter, page, size);
    }

    @Operation(summary = "Historial de movimientos",
            description = "Del más reciente al más antiguo; se puede filtrar por producto y tipo.")
    @GetMapping("/movements")
    @RequirePermission(Permission.INVENTORY_VIEW)
    public PageResponse<InventoryService.MovementView> movements(@RequestParam(required = false) Long productId,
                                                                 @RequestParam(required = false) String type,
                                                                 @RequestParam(defaultValue = "0") int page,
                                                                 @RequestParam(defaultValue = "30") int size) {
        return inventoryService.movements(accessService.current().companyId(), productId, type, page, size);
    }

    @Operation(summary = "Registrar un movimiento",
            description = "type ENTRY suma, EXIT resta y ADJUSTMENT fija el stock contado. EXIT y ADJUSTMENT exigen motivo.")
    @PostMapping("/products/{productId}/movements")
    @RequirePermission(Permission.INVENTORY_ADJUST)
    public InventoryService.MovementView adjust(@PathVariable Long productId,
                                                @RequestBody InventoryService.AdjustRequest request) {
        Member member = accessService.current();
        InventoryService.MovementView movement = inventoryService.adjust(member.company(), productId, request, member.displayName());
        auditService.record(member, com.fluxyBackend.service.AuditAction.INVENTORY_ADJUSTED, "PRODUCT", productId, null);
        return movement;
    }

    @Operation(summary = "Cambiar el stock mínimo", description = "Por debajo de este valor el producto aparece como stock bajo.")
    @PutMapping("/products/{productId}/min-stock")
    @RequirePermission(Permission.INVENTORY_ADJUST)
    public InventoryService.StockItem minStock(@PathVariable Long productId, @RequestBody MinStockRequest request) {
        return inventoryService.updateMinStock(accessService.current().company(), productId, request.minStock());
    }
}
