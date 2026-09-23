package com.fluxyBackend.controller;

import com.fluxyBackend.billing.BillingPayment;
import com.fluxyBackend.billing.PaymentProvider;
import com.fluxyBackend.billing.PlanCatalog;
import com.fluxyBackend.billing.SubscriptionService;
import com.fluxyBackend.billing.SubscriptionService.ActivationResult;
import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.security.access.AccessService;
import com.fluxyBackend.security.access.Permission;
import com.fluxyBackend.security.access.RequirePermission;
import com.fluxyBackend.service.BusinessClock;
import com.fluxyBackend.service.EmailService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Checkout y webhook de Mercado Pago. La lógica de planes vive en SubscriptionService;
 * acá solo se valida la notificación y se consulta el pago en el proveedor.
 */
@Tag(name = "Pagos", description = "Compra de planes y confirmación de pagos mediante Mercado Pago.")
@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
@Slf4j
public class MercadoPagoController {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d 'de' MMMM", Locale.forLanguageTag("es-PE"));

    private final SubscriptionService subscriptionService;
    private final PaymentProvider paymentProvider;
    private final AccessService accessService;
    private final EmailService emailService;

    @Operation(summary = "Crear una preferencia de pago (compatibilidad)",
            description = "Equivale a POST /billing/subscription/checkout. body: plan (PRO o BUSINESS) y months (1 a 12, como texto). "
                    + "Crear la preferencia no cambia el plan: eso ocurre cuando Mercado Pago confirma el pago.")
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/create-preference")
    @RequirePermission(Permission.BILLING_MANAGE)
    public Map<String, String> createPreference(@RequestBody Map<String, String> body) {
        int months;
        try {
            months = Integer.parseInt(body.getOrDefault("months", "1"));
        } catch (NumberFormatException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MONTHS_INVALID", "La cantidad de meses debe estar entre 1 y 12.");
        }
        SubscriptionService.CheckoutResponse checkout =
                subscriptionService.checkout(accessService.current(), body.getOrDefault("plan", "PRO"), months);
        return Map.of("initPoint", checkout.checkoutUrl(),
                "sandboxUrl", checkout.sandboxUrl() == null ? "" : checkout.sandboxUrl());
    }

    @Operation(summary = "Recibir una notificación de Mercado Pago",
            description = "No requiere JWT. Para eventos payment valida x-signature con el secreto configurado y consulta el pago "
                    + "en Mercado Pago; nunca se confía en los datos del retorno del navegador. Los demás eventos se ignoran con 200. "
                    + "Es idempotente: el mismo pago no suma meses dos veces.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = "{\"type\":\"payment\",\"data\":{\"id\":\"123456789\"}}"))))
    @PostMapping("/webhook")
    public ResponseEntity<Void> webhook(
            @RequestBody(required = false) Map<String, Object> body,
            @Parameter(description = "Tipo de evento; tiene prioridad sobre type del cuerpo.") @RequestParam(required = false) String type,
            @Parameter(description = "ID alternativo de pago.") @RequestParam(required = false) String id,
            @Parameter(description = "ID de pago prioritario.") @RequestParam(name = "data.id", required = false) String queryDataId,
            @RequestHeader(name = "x-signature", required = false) String signature,
            @RequestHeader(name = "x-request-id", required = false) String requestId) {

        String topic = type != null ? type : bodyValue(body, "type");
        if (!"payment".equals(topic)) return ResponseEntity.ok().build();

        String paymentId = firstNonBlank(queryDataId, nestedDataId(body), id);
        if (paymentId == null) return ResponseEntity.badRequest().build();

        try {
            if (!paymentProvider.verifyWebhook(signature, requestId, paymentId)) {
                log.warn("Webhook de Mercado Pago con firma inválida");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            Optional<ActivationResult> result = subscriptionService.applyPayment(paymentProvider.fetchPayment(paymentId));
            result.ifPresent(this::sendConfirmation);
            return ResponseEntity.ok().build();
        } catch (BusinessException e) {
            return ResponseEntity.status(e.getStatus()).build();
        } catch (Exception e) {
            log.error("Error procesando webhook de Mercado Pago para pago {}", paymentId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private void sendConfirmation(ActivationResult result) {
        if (result.ownerEmail() == null) return;
        if (result.kind() == BillingPayment.Kind.DOWNGRADE) {
            String planName = PlanCatalog.info(result.plan()).name();
            emailService.sendBillingNotice(result.ownerEmail(), result.ownerName(), "Programaste el cambio a " + planName,
                    "Recibimos tu pago. Seguís con tu plan actual hasta el " + BusinessClock.withOffset(result.effectiveAt()).format(DAY)
                            + " y ese día empieza " + planName + ", pagado hasta el "
                            + BusinessClock.withOffset(result.paidUntil()).format(DAY) + ".");
        } else {
            emailService.sendPlanActivatedEmail(result.ownerEmail(), result.ownerName(), result.plan().name(), result.paidUntil());
        }
        log.info("Pago aplicado: plan {} ({}) para company {}", result.plan(), result.kind(), result.companyId());
    }

    private String nestedDataId(Map<String, Object> body) {
        if (body == null) return null;
        Object data = body.get("data");
        if (data instanceof Map<?, ?> dataMap) {
            Object value = dataMap.get("id");
            return value == null ? null : String.valueOf(value);
        }
        return null;
    }

    private String bodyValue(Map<String, Object> body, String key) {
        return body == null || body.get(key) == null ? null : String.valueOf(body.get(key));
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }
}
