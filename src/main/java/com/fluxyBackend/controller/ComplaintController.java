package com.fluxyBackend.controller;

import com.fluxyBackend.entity.Complaint;
import com.fluxyBackend.security.ClientInfo;
import com.fluxyBackend.service.ComplaintService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@Tag(name = "Libro de Reclamaciones")
public class ComplaintController {

    private final ComplaintService complaintService;

    public record ResponseRequest(String response) {}

    @Operation(summary = "Datos del proveedor", description = "Razón social, RUC y domicilio que encabezan la hoja.")
    @GetMapping("/complaints/provider")
    public ComplaintService.ProviderInfo provider() {
        return complaintService.provider();
    }

    @Operation(summary = "Registrar una hoja de reclamación",
            description = "Público. Devuelve el código de constancia; la copia se envía al correo indicado.")
    @PostMapping("/complaints")
    @ResponseStatus(HttpStatus.CREATED)
    public ComplaintService.Receipt submit(@Valid @RequestBody ComplaintService.ComplaintRequest request,
                                           HttpServletRequest http) {
        return complaintService.submit(request, ClientInfo.ipHash(ClientInfo.ip(http)));
    }

    @Operation(summary = "Listar hojas de reclamación", description = "Solo administración. Ordenadas por fecha límite.")
    @GetMapping("/admin/complaints")
    public Map<String, Object> list(@RequestParam(required = false) Complaint.Status status,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        Page<ComplaintService.ComplaintView> result = complaintService.list(status, page, size);
        return Map.of("content", result.getContent(), "page", result.getNumber(), "size", result.getSize(),
                "totalElements", result.getTotalElements(), "totalPages", result.getTotalPages(),
                "pending", complaintService.pendingCount());
    }

    @Operation(summary = "Responder una hoja de reclamación",
            description = "Solo administración. Envía la respuesta al consumidor.")
    @PostMapping("/admin/complaints/{id}/response")
    public ComplaintService.ComplaintView respond(@PathVariable Long id, @RequestBody ResponseRequest request,
                                                  Authentication auth) {
        return complaintService.respond(id, request.response(), auth.getName());
    }
}
