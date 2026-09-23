package com.fluxyBackend.billing;

import com.fluxyBackend.security.access.AccessService;
import com.fluxyBackend.security.access.Permission;
import com.fluxyBackend.security.access.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Plan y facturación de la empresa del usuario. La empresa sale del contexto autenticado,
 * nunca de la petición. Solo el dueño (BILLING_MANAGE) consulta o cambia la suscripción.
 */
@Tag(name = "Plan y facturación", description = "Planes, suscripción, cambios, cancelación al fin del periodo e historial.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/billing")
@RequiredArgsConstructor
public class BillingController {

    private final SubscriptionService subscriptionService;
    private final AccessService accessService;

    public record CheckoutRequest(String plan, Integer months) {}

    public record CancelRequest(String reason, String comment) {}

    @Operation(summary = "Planes disponibles", description = "Precio mensual en PEN, límite de productos (-1 = sin límite) y funciones.")
    @GetMapping("/plans")
    public List<SubscriptionService.PlanView> plans() {
        return subscriptionService.plans();
    }

    @Operation(summary = "Suscripción actual",
            description = "Plan vigente, estado, periodo, hasta cuándo está pagado, cancelación pedida y cambio programado.")
    @GetMapping("/subscription")
    @RequirePermission(Permission.BILLING_MANAGE)
    public SubscriptionService.View subscription() {
        return subscriptionService.view(accessService.current().company());
    }

    @Operation(summary = "Uso frente a los límites del plan")
    @GetMapping("/usage")
    @RequirePermission(Permission.BILLING_MANAGE)
    public SubscriptionService.Usage usage() {
        return subscriptionService.usage(accessService.current().company());
    }

    @Operation(summary = "Cotizar un pago",
            description = "Qué pasaría al pagar: tipo (NEW, RENEWAL, UPGRADE, DOWNGRADE), importe, fechas, días convertidos y avisos.")
    @GetMapping("/subscription/quote")
    @RequirePermission(Permission.BILLING_MANAGE)
    public SubscriptionService.Quote quote(@RequestParam String plan, @RequestParam(defaultValue = "1") int months) {
        return subscriptionService.quote(accessService.current().company(), plan, months);
    }

    @Operation(summary = "Iniciar el pago de un plan",
            description = "Devuelve la URL de Mercado Pago. El plan cambia recién cuando el proveedor confirma el pago por webhook; "
                    + "el backend decide si es alta, renovación, subida o bajada. Acepta Idempotency-Key.")
    @PostMapping("/subscription/checkout")
    @RequirePermission(Permission.BILLING_MANAGE)
    public SubscriptionService.CheckoutResponse checkout(@RequestBody CheckoutRequest request) {
        int months = request.months() == null ? 1 : request.months();
        return subscriptionService.checkout(accessService.current(), request.plan(), months);
    }

    @Operation(summary = "Cancelar la suscripción",
            description = "Al final del periodo: el plan sigue activo hasta el fin de lo pagado y después pasa a Free. "
                    + "No borra datos. Repetirla devuelve el mismo resultado. reason: TOO_EXPENSIVE, NOT_USING, "
                    + "MISSING_FEATURES, SWITCHING, TEMPORARY u OTHER.")
    @PostMapping("/subscription/cancel")
    @RequirePermission(Permission.BILLING_MANAGE)
    public SubscriptionService.View cancel(@RequestBody(required = false) CancelRequest request) {
        return subscriptionService.cancel(accessService.current(),
                request == null ? null : request.reason(), request == null ? null : request.comment());
    }

    @Operation(summary = "Deshacer la cancelación", description = "Solo mientras el periodo pagado sigue vigente.")
    @PostMapping("/subscription/reactivate")
    @RequirePermission(Permission.BILLING_MANAGE)
    public SubscriptionService.View reactivate() {
        return subscriptionService.reactivate(accessService.current());
    }

    @Operation(summary = "Activar la prueba gratuita", description = "Un mes del plan Pro, una sola vez por negocio.")
    @PostMapping("/subscription/trial")
    @RequirePermission(Permission.BILLING_MANAGE)
    public SubscriptionService.View trial() {
        return subscriptionService.startTrial(accessService.current());
    }

    @Operation(summary = "Historial de facturación", description = "Cambios de la suscripción y cobros, los más recientes primero.")
    @GetMapping("/history")
    @RequirePermission(Permission.BILLING_MANAGE)
    public SubscriptionService.History history() {
        return subscriptionService.history(accessService.current().company());
    }
}
