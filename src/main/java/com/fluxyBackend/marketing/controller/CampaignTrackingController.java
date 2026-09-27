package com.fluxyBackend.marketing.controller;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.exception.NotFoundException;
import com.fluxyBackend.marketing.dto.TrackEventRequest;
import com.fluxyBackend.marketing.dto.TrackEventResponse;
import com.fluxyBackend.marketing.service.CampaignTrackingService;
import com.fluxyBackend.repository.CompanyRepository;
import com.fluxyBackend.service.CompanyLifecycleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Eventos del embudo desde la tienda pública. Sin sesión; limitado por IP (RateLimitFilter,
 * bucket STORE_TRACKING) y deduplicado por navegador y día. El pedido no pasa por acá:
 * lo atribuye el servidor al crearlo.
 */
@Tag(name = "Tienda pública", description = "Catálogo y pedidos de clientes sin autenticación.")
@RestController
@RequestMapping("/store")
@RequiredArgsConstructor
public class CampaignTrackingController {

    private final CompanyRepository companies;
    private final CompanyLifecycleService lifecycle;
    private final CampaignTrackingService tracking;

    @Operation(summary = "Registrar un paso del embudo de una campaña",
            description = "VIEW al entrar con ?cmp=código (devuelve qué abrir y el cupón a sugerir); PRODUCT_VIEW, ADD_TO_CART y CHECKOUT_STARTED solo cuentan si antes hubo una VIEW del mismo navegador. accepted=false si la campaña no existe, es de otra tienda o no está vigente.")
    @PostMapping("/{companyId}/events")
    public TrackEventResponse track(@PathVariable Long companyId, @Valid @RequestBody TrackEventRequest request) {
        Company company = companies.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Empresa no encontrada"));
        lifecycle.ensureStoreOnline(company);
        return tracking.track(company, request);
    }
}
