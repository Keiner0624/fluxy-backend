package com.fluxyBackend.controller;

import com.fluxyBackend.security.access.AccessService;
import com.fluxyBackend.security.access.Permission;
import com.fluxyBackend.security.access.RequirePermission;
import com.fluxyBackend.service.IntegrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Integraciones", description = "WhatsApp, Google Analytics, Meta Pixel y Mercado Pago.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/integrations")
@RequiredArgsConstructor
public class IntegrationController {

    private final IntegrationService integrationService;
    private final AccessService accessService;

    @Operation(summary = "Estado de las integraciones")
    @GetMapping
    @RequirePermission(Permission.INTEGRATION_VIEW)
    public IntegrationService.Overview overview() {
        return integrationService.overview(accessService.current().company());
    }

    @Operation(summary = "Configurar WhatsApp",
            description = "enabled activa o pausa el aviso y el enlace de pedidos; number es el WhatsApp del negocio.")
    @PutMapping("/whatsapp")
    @RequirePermission(Permission.INTEGRATION_MANAGE)
    public IntegrationService.Overview whatsapp(@RequestBody IntegrationService.WhatsAppRequest request) {
        return integrationService.updateWhatsApp(accessService.current().company(), request);
    }

    @Operation(summary = "Configurar Google Analytics", description = "id con formato G-XXXXXXXXXX; vacío desconecta.")
    @PutMapping("/google-analytics")
    @RequirePermission(Permission.INTEGRATION_MANAGE)
    public IntegrationService.Overview googleAnalytics(@RequestBody IntegrationService.TrackingRequest request) {
        return integrationService.updateGoogleAnalytics(accessService.current().company(), request);
    }

    @Operation(summary = "Configurar Meta Pixel", description = "id numérico del píxel; vacío desconecta.")
    @PutMapping("/meta-pixel")
    @RequirePermission(Permission.INTEGRATION_MANAGE)
    public IntegrationService.Overview metaPixel(@RequestBody IntegrationService.TrackingRequest request) {
        return integrationService.updateMetaPixel(accessService.current().company(), request);
    }
}
