package com.fluxyBackend.domain;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Tienda pública", description = "Catálogo y pedidos de clientes sin autenticación.")
@RestController
@RequestMapping("/store")
@RequiredArgsConstructor
public class StoreDomainController {

    private final CustomDomainService domains;

    @Operation(summary = "Tienda de un dominio propio",
            description = "La tienda la llama al abrirse en un dominio que no es de Fluxy (mitienda.com o www.mitienda.com). "
                    + "active=false si el plan ya no incluye dominio propio: la tienda redirige a storeUrl. 404 si ninguna tienda lo usa.")
    @GetMapping("/domain")
    public CustomDomainService.ResolvedStore resolve(@RequestParam String host) {
        return domains.resolve(host);
    }
}
