package com.fluxyBackend.controller;

import com.fluxyBackend.exception.ForbiddenException;
import com.fluxyBackend.security.RateLimitService;
import com.fluxyBackend.security.access.AccessService;
import com.fluxyBackend.security.access.Member;
import com.fluxyBackend.security.access.Permission;
import com.fluxyBackend.security.access.RequirePermission;
import com.fluxyBackend.service.AuditAction;
import com.fluxyBackend.service.AuditService;
import com.fluxyBackend.service.CompanyLifecycleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Estado del negocio, reactivación, eliminación y exportación de datos. */
@Tag(name = "Cuenta del negocio", description = "Ciclo de vida, eliminación y exportación de los datos del negocio.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/company-account")
@RequiredArgsConstructor
public class CompanyAccountController {

    private final AccessService accessService;
    private final CompanyLifecycleService lifecycleService;
    private final RateLimitService rateLimitService;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;

    @Operation(summary = "Estado del negocio", description = "ACTIVE, INACTIVE, SUSPENDED, ARCHIVED o DELETION_PENDING.")
    @GetMapping("/lifecycle")
    public CompanyLifecycleService.LifecycleView lifecycle() {
        return lifecycleService.view(accessService.current().company());
    }

    @Operation(summary = "Reactivar el negocio", description = "Vuelve a ACTIVE una tienda inactiva, suspendida o archivada.")
    @PostMapping("/lifecycle/reactivate")
    @RequirePermission(Permission.SETTINGS_MANAGE)
    public CompanyLifecycleService.LifecycleView reactivate() {
        return lifecycleService.reactivate(accessService.current());
    }

    @Operation(summary = "Programar la eliminación del negocio",
            description = "Solo el dueño, con identidad confirmada (403 REAUTH_REQUIRED) y confirmation igual al nombre del negocio. "
                    + "La tienda sale de línea y se elimina al terminar el período de gracia.")
    @PostMapping("/deletion")
    public CompanyLifecycleService.LifecycleView requestDeletion(@RequestBody CompanyLifecycleService.DeletionRequest request) {
        return lifecycleService.requestDeletion(accessService.current(), accessService.currentSessionId(), request);
    }

    @Operation(summary = "Cancelar la eliminación")
    @DeleteMapping("/deletion")
    public CompanyLifecycleService.LifecycleView cancelDeletion() {
        return lifecycleService.cancelDeletion(accessService.current());
    }

    @Operation(summary = "Exportar los datos del negocio",
            description = "JSON con productos, categorías, clientes, pedidos, pagos y movimientos. Solo el dueño; 5 por hora.")
    @GetMapping("/export")
    public ResponseEntity<Map<String, Object>> export() {
        Member member = accessService.current();
        if (!member.isOwner()) {
            throw new ForbiddenException(ForbiddenException.OWNER_ONLY, "Solo el dueño puede exportar todos los datos.");
        }
        rateLimitService.check(RateLimitService.Bucket.DATA_EXPORT, "company:" + member.companyId());
        Long id = member.companyId();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("exportedAt", OffsetDateTime.now().toString());
        data.put("company", first("SELECT id, name, slug, email, phone, address, description, plan, plan_expires_at, custom_domain FROM company WHERE id = ?", id));
        data.put("categories", rows("SELECT * FROM categories WHERE company_id = ? ORDER BY id", id));
        data.put("products", rows("SELECT * FROM products WHERE company_id = ? ORDER BY id", id));
        data.put("customers", rows("SELECT * FROM customers WHERE company_id = ? ORDER BY id", id));
        data.put("orders", rows("SELECT * FROM orders WHERE company_id = ? ORDER BY id", id));
        data.put("orderItems", rows("SELECT i.* FROM order_items i JOIN orders o ON o.id = i.order_id WHERE o.company_id = ? ORDER BY i.id", id));
        data.put("payments", rows("SELECT * FROM order_payments WHERE company_id = ? ORDER BY id", id));
        data.put("inventoryMovements", rows("SELECT * FROM inventory_movements WHERE company_id = ? ORDER BY id", id));
        data.put("coupons", rows("SELECT * FROM coupons WHERE company_id = ? ORDER BY id", id));

        auditService.record(member, AuditAction.DATA_EXPORTED, "COMPANY", id, null);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"fluxy-export-" + LocalDate.now() + ".json\"")
                .body(data);
    }

    private List<Map<String, Object>> rows(String sql, Long companyId) {
        try {
            return jdbc.queryForList(sql, companyId);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private Map<String, Object> first(String sql, Long companyId) {
        List<Map<String, Object>> rows = rows(sql, companyId);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }
}
