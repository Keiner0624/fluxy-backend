package com.fluxyBackend.controller;

import com.fluxyBackend.DTOs.PageResponse;
import com.fluxyBackend.security.access.AccessService;
import com.fluxyBackend.security.access.Permission;
import com.fluxyBackend.security.access.RequirePermission;
import com.fluxyBackend.service.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "Actividad", description = "Registro de auditoría del negocio: accesos, cambios de equipo y operaciones sensibles.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AccessService accessService;
    private final AuditService auditService;

    @Operation(summary = "Listar la actividad", description = "Filtros opcionales por acción, persona y rango de fechas. Más reciente primero.")
    @GetMapping
    @RequirePermission(Permission.AUDIT_VIEW)
    public PageResponse<AuditService.AuditView> list(@RequestParam(required = false) String action,
                                                     @RequestParam(required = false) Long actorUserId,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                     @RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "25") int size) {
        return auditService.list(accessService.current().companyId(), action, actorUserId, from, to, page, size);
    }

    @Operation(summary = "Acciones registradas", description = "Valores posibles para el filtro action.")
    @GetMapping("/actions")
    @RequirePermission(Permission.AUDIT_VIEW)
    public List<String> actions() {
        return auditService.actions(accessService.current().companyId());
    }
}
