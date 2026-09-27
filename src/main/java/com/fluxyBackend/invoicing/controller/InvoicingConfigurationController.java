package com.fluxyBackend.invoicing.controller;

import com.fluxyBackend.invoicing.dto.InvoicingConfigurationView;
import com.fluxyBackend.invoicing.dto.InvoicingSettingsRequest;
import com.fluxyBackend.invoicing.dto.InvoicingStatusView;
import com.fluxyBackend.invoicing.dto.ProviderSettingsRequest;
import com.fluxyBackend.invoicing.dto.SeriesRequest;
import com.fluxyBackend.invoicing.dto.VerifyRucRequest;
import com.fluxyBackend.invoicing.service.InvoicingConfigurationService;
import com.fluxyBackend.security.access.AccessService;
import com.fluxyBackend.security.access.Permission;
import com.fluxyBackend.security.access.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Configuración fiscal y de emisión. La empresa sale de la sesión. Las capacidades (qué se puede
 * emitir) y el estado ACTIVE los calcula el servidor: ningún campo del cuerpo las fija.
 */
@Tag(name = "Facturación electrónica: configuración", description = "RUC, verificación, proveedor, series, prueba y activación.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/invoicing")
@RequiredArgsConstructor
public class InvoicingConfigurationController {

    private final InvoicingConfigurationService configuration;
    private final AccessService access;

    @Operation(summary = "Estado de la facturación", description = "Si está activa y qué comprobantes se pueden emitir. Para quien ve o emite comprobantes.")
    @GetMapping("/status")
    @RequirePermission(Permission.INVOICE_VIEW)
    public InvoicingStatusView status() {
        return configuration.status(access.current());
    }

    @Operation(summary = "Configuración completa", description = "Datos fiscales, perfil verificado, proveedor (sin credenciales), series y checklist de activación.")
    @GetMapping("/configuration")
    @RequirePermission(Permission.INVOICING_CONFIGURE)
    public InvoicingConfigurationView get() {
        return configuration.view(access.current());
    }

    @Operation(summary = "Actualizar datos y preferencias",
            description = "Nombre comercial, dirección fiscal, régimen, emisión automática, disparador, correo, adjuntos, impresión y afectación al IGV.")
    @PutMapping("/configuration")
    @RequirePermission(Permission.INVOICING_CONFIGURE)
    public InvoicingConfigurationView update(@RequestBody InvoicingSettingsRequest request) {
        return configuration.updateSettings(access.current(), request);
    }

    @Operation(summary = "Verificar el RUC",
            description = "Valida el formato y consulta el padrón en el servidor. Cambiar un RUC ya verificado exige identidad confirmada (403 REAUTH_REQUIRED).")
    @PostMapping("/configuration/verify-ruc")
    @RequirePermission(Permission.INVOICING_CONFIGURE)
    public InvoicingConfigurationView verifyRuc(@RequestBody VerifyRucRequest request) {
        return configuration.verifyRuc(access.current(), request, access.currentSessionId());
    }

    @Operation(summary = "Proveedor y credenciales",
            description = "SANDBOX (modo de prueba) o NUBEFACT (ruta y token de la cuenta del negocio). El token se guarda cifrado y nunca se devuelve. Guardar credenciales exige identidad confirmada.")
    @PutMapping("/configuration/provider")
    @RequirePermission(Permission.INVOICING_CONFIGURE)
    public InvoicingConfigurationView provider(@RequestBody ProviderSettingsRequest request) {
        return configuration.updateProvider(access.current(), request, access.currentSessionId());
    }

    @Operation(summary = "Probar la conexión con el proveedor")
    @PostMapping("/configuration/test")
    @RequirePermission(Permission.INVOICING_CONFIGURE)
    public InvoicingConfigurationView test() {
        return configuration.testConnection(access.current());
    }

    @Operation(summary = "Activar la facturación", description = "409 INVOICING_NOT_READY con la lista de lo que falta.")
    @PostMapping("/configuration/activate")
    @RequirePermission(Permission.INVOICING_CONFIGURE)
    public InvoicingConfigurationView activate() {
        return configuration.activate(access.current());
    }

    @Operation(summary = "Pausar la facturación", description = "Deja de emitir; lo emitido no cambia.")
    @PostMapping("/configuration/pause")
    @RequirePermission(Permission.INVOICING_CONFIGURE)
    public InvoicingConfigurationView pause() {
        return configuration.pause(access.current());
    }

    @Operation(summary = "Crear una serie", description = "Boleta: B + 3 caracteres; factura: F + 3; nota de crédito: B o F + 3.")
    @PostMapping("/series")
    @RequirePermission(Permission.INVOICING_SERIES)
    public InvoicingConfigurationView createSeries(@RequestBody SeriesRequest request) {
        return configuration.createSeries(access.current(), request);
    }

    @Operation(summary = "Habilitar o ajustar una serie",
            description = "El correlativo solo se ajusta antes de emitir con esa serie (para continuar una numeración anterior).")
    @PutMapping("/series/{id}")
    @RequirePermission(Permission.INVOICING_SERIES)
    public InvoicingConfigurationView updateSeries(@PathVariable Long id,
                                                   @RequestBody SeriesRequest request) {
        return configuration.updateSeries(access.current(), id, request);
    }
}
