package com.fluxyBackend.marketing.controller;

import com.fluxyBackend.marketing.dto.CampaignAnalytics;
import com.fluxyBackend.marketing.dto.CampaignLink;
import com.fluxyBackend.marketing.dto.CampaignRequest;
import com.fluxyBackend.marketing.dto.CampaignView;
import com.fluxyBackend.marketing.dto.MarketingOverview;
import com.fluxyBackend.marketing.dto.Opportunity;
import com.fluxyBackend.marketing.dto.SegmentView;
import com.fluxyBackend.marketing.service.CampaignService;
import com.fluxyBackend.marketing.service.OpportunityService;
import com.fluxyBackend.marketing.service.SegmentService;
import com.fluxyBackend.security.access.AccessService;
import com.fluxyBackend.security.access.Member;
import com.fluxyBackend.security.access.Permission;
import com.fluxyBackend.security.access.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Módulo Marketing del panel. La empresa sale siempre de la sesión (AccessService), nunca de
 * la petición; cada ruta exige su permiso MARKETING_* y los límites del plan se validan en el servicio.
 */
@Tag(name = "Marketing", description = "Campañas, enlaces rastreables, resultados atribuidos, segmentos y oportunidades.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/marketing")
@RequiredArgsConstructor
public class MarketingController {

    private final CampaignService campaigns;
    private final SegmentService segments;
    private final OpportunityService opportunities;
    private final AccessService access;

    public record LinkRequest(String channel) {}

    @Operation(summary = "Resumen de marketing",
            description = "Visitas, pedidos y ventas atribuidas del periodo (días, 30 por defecto), campañas en curso frente al límite del plan y capacidades del plan.")
    @GetMapping("/overview")
    @RequirePermission(Permission.MARKETING_VIEW)
    public MarketingOverview overview(@RequestParam(defaultValue = "30") int days) {
        Member member = access.current();
        MarketingOverview overview = campaigns.overview(member, days);
        // Sin permiso de resultados solo se ve el uso del plan.
        return member.can(Permission.MARKETING_ANALYTICS) ? overview
                : new MarketingOverview(overview.from(), overview.to(), null, overview.liveCampaigns(),
                overview.liveCampaignLimit(), overview.plan(), overview.capabilities());
    }

    @Operation(summary = "Listar campañas", description = "De la más nueva a la más vieja. metrics viene null sin MARKETING_ANALYTICS.")
    @GetMapping("/campaigns")
    @RequirePermission(Permission.MARKETING_VIEW)
    public List<CampaignView> list() {
        return campaigns.list(access.current());
    }

    @Operation(summary = "Crear campaña", description = "Queda en borrador (DRAFT). type: STORE, PRODUCT, CATEGORY o COUPON.")
    @PostMapping("/campaigns")
    @RequirePermission(Permission.MARKETING_CREATE)
    public CampaignView create(@Valid @RequestBody CampaignRequest request) {
        return campaigns.create(access.current(), request);
    }

    @Operation(summary = "Detalle de una campaña")
    @GetMapping("/campaigns/{id}")
    @RequirePermission(Permission.MARKETING_VIEW)
    public CampaignView get(@PathVariable Long id) {
        return campaigns.get(access.current(), id);
    }

    @Operation(summary = "Editar campaña",
            description = "Reemplaza el contenido. Qué se promociona y el canal solo cambian en borrador. 409 si está finalizada o archivada.")
    @PatchMapping("/campaigns/{id}")
    @RequirePermission(Permission.MARKETING_EDIT)
    public CampaignView update(@PathVariable Long id, @Valid @RequestBody CampaignRequest request) {
        return campaigns.update(access.current(), id, request);
    }

    @Operation(summary = "Eliminar un borrador", description = "Las campañas publicadas se finalizan y archivan; no se eliminan.")
    @DeleteMapping("/campaigns/{id}")
    @RequirePermission(Permission.MARKETING_EDIT)
    public ResponseEntity<Map<String, String>> delete(@PathVariable Long id) {
        campaigns.delete(access.current(), id);
        return ResponseEntity.ok(Map.of("message", "Borrador eliminado."));
    }

    @Operation(summary = "Activar campaña",
            description = "Desde DRAFT, PAUSED o SCHEDULED. Con inicio futuro queda SCHEDULED. 403 CAMPAIGN_LIMIT si se supera el límite del plan.")
    @PostMapping("/campaigns/{id}/activate")
    @RequirePermission(Permission.MARKETING_PUBLISH)
    public CampaignView activate(@PathVariable Long id) {
        return campaigns.activate(access.current(), id);
    }

    @Operation(summary = "Pausar campaña", description = "Deja de atribuir visitas y pedidos hasta reactivarla.")
    @PostMapping("/campaigns/{id}/pause")
    @RequirePermission(Permission.MARKETING_PUBLISH)
    public CampaignView pause(@PathVariable Long id) {
        return campaigns.pause(access.current(), id);
    }

    @Operation(summary = "Finalizar campaña", description = "Definitivo: conserva los resultados y deja de atribuir.")
    @PostMapping("/campaigns/{id}/finish")
    @RequirePermission(Permission.MARKETING_PUBLISH)
    public CampaignView finish(@PathVariable Long id) {
        return campaigns.finish(access.current(), id);
    }

    @Operation(summary = "Archivar campaña", description = "Borradores o finalizadas: salen de la lista principal y quedan en el historial.")
    @PostMapping("/campaigns/{id}/archive")
    @RequirePermission(Permission.MARKETING_PUBLISH)
    public CampaignView archive(@PathVariable Long id) {
        return campaigns.archive(access.current(), id);
    }

    @Operation(summary = "Resultados de una campaña",
            description = "Rango opcional en días del negocio (from, to: yyyy-MM-dd). Embudo, canales, serie diaria y productos requieren analítica completa (Pro).")
    @GetMapping("/campaigns/{id}/analytics")
    @RequirePermission(Permission.MARKETING_ANALYTICS)
    public CampaignAnalytics analytics(@PathVariable Long id,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return campaigns.analytics(access.current(), id, from, to);
    }

    @Operation(summary = "Generar enlace rastreable",
            description = "Enlace a la tienda con el código de la campaña y utm_* del canal (por defecto, el de la campaña), texto listo para compartir y enlace de compartir del canal.")
    @PostMapping("/campaigns/{id}/links")
    @RequirePermission(Permission.MARKETING_VIEW)
    public CampaignLink link(@PathVariable Long id, @RequestBody(required = false) LinkRequest request) {
        return campaigns.link(access.current(), id, request == null ? null : request.channel());
    }

    @Operation(summary = "Oportunidades detectadas", description = "Hasta 4, con prioridad, motivo y la campaña sugerida.")
    @GetMapping("/opportunities")
    @RequirePermission(Permission.MARKETING_VIEW)
    public List<Opportunity> opportunities() {
        return opportunities.detect(access.current().company());
    }

    @Operation(summary = "Segmentos disponibles",
            description = "Cantidad de clientes y de contactables (con teléfono) por segmento; excluye a quienes pidieron no recibir promociones.")
    @GetMapping("/segments")
    @RequirePermission(Permission.MARKETING_VIEW)
    public List<SegmentView> segments() {
        return segments.segments(access.current().company());
    }

    @Operation(summary = "Clientes de un segmento",
            description = "Hasta 500, para contactarlos uno a uno. Requiere además CUSTOMER_VIEW. categoryId en CATEGORY_BUYERS.")
    @GetMapping("/segments/{key}/customers")
    @RequirePermission({Permission.MARKETING_VIEW, Permission.CUSTOMER_VIEW})
    public List<SegmentView.Customer> segmentCustomers(@PathVariable String key,
                                                       @RequestParam(required = false) Long categoryId) {
        return segments.customers(access.current().company(), SegmentService.parse(key), categoryId);
    }
}
