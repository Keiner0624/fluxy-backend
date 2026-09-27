package com.fluxyBackend.invoicing.controller;

import com.fluxyBackend.invoicing.dto.WebhookResult;
import com.fluxyBackend.invoicing.service.InvoicingWebhookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** Avisos firmados de los proveedores de facturación. Sin sesión: la autenticación es la firma. */
@Tag(name = "Facturación electrónica: webhooks", description = "Cambios de estado informados por el proveedor.")
@RestController
@RequestMapping("/webhooks/invoicing")
@RequiredArgsConstructor
public class InvoicingWebhookController {

    private final InvoicingWebhookService webhooks;

    @Operation(summary = "Recibir un aviso de estado",
            description = "Cabecera X-Fluxy-Signature: t=<unix>,v1=<HMAC-SHA256 hex de t.cuerpo>. 401 sin firma válida o fuera de los 5 minutos; un eventId repetido responde duplicate=true sin volver a aplicarse.")
    @PostMapping("/{provider}")
    public WebhookResult receive(@PathVariable String provider,
                                 @RequestHeader(name = "X-Fluxy-Signature", required = false) String signature,
                                 @RequestBody String body) {
        return webhooks.handle(provider, signature, body);
    }
}
