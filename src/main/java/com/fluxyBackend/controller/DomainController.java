package com.fluxyBackend.controller;

import com.fluxyBackend.domain.CustomDomainService;
import com.fluxyBackend.security.access.AccessService;
import com.fluxyBackend.security.access.Permission;
import com.fluxyBackend.security.access.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "Dominios", description = "Dominio propio de la tienda (plan Business).")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/domains")
@RequiredArgsConstructor
public class DomainController {

    private final CustomDomainService domains;
    private final AccessService accessService;

    @Operation(summary = "Estado del dominio propio",
            description = "Consulta a Vercel y devuelve el estado (NONE, PENDING_DNS, VERIFICATION_REQUIRED, ACTIVE o ERROR) "
                    + "y los registros DNS que faltan agregar.")
    @GetMapping({"", "/status"})
    @RequirePermission(Permission.SETTINGS_MANAGE)
    public CustomDomainService.DomainView status() {
        return domains.view(accessService.current());
    }

    @Operation(summary = "Conectar un dominio propio",
            description = "Requiere plan Business. Un dominio raíz se conecta junto con www, que redirige a él. "
                    + "Responde 409 si el dominio ya está en otra tienda o hay otro conectado.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = "{\"domain\":\"mitienda.com\"}"))))
    @PostMapping({"", "/add"})
    @RequirePermission(Permission.SETTINGS_MANAGE)
    public CustomDomainService.DomainView connect(@RequestBody CustomDomainService.ConnectRequest request) {
        return domains.connect(accessService.current(), request);
    }

    @Operation(summary = "Verificar el dominio ahora", description = "Vuelve a consultar los DNS en Vercel.")
    @PostMapping("/verify")
    @RequirePermission(Permission.SETTINGS_MANAGE)
    public CustomDomainService.DomainView verify() {
        return domains.verify(accessService.current());
    }

    @Operation(summary = "Quitar el dominio propio",
            description = "Lo desconecta de Vercel y de la tienda; la tienda sigue en su dirección de Fluxy.")
    @DeleteMapping({"", "/remove"})
    @RequirePermission(Permission.SETTINGS_MANAGE)
    public Map<String, String> remove() {
        domains.remove(accessService.current());
        return Map.of("message", "Dominio quitado. Tu tienda sigue disponible en su dirección de Fluxy.");
    }
}
