package com.fluxyBackend.controller;

import com.fluxyBackend.entity.Company.Plan;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.repository.UserRepository;
import com.fluxyBackend.service.EmailService;
import com.fluxyBackend.service.PaymentActivationService;
import com.fluxyBackend.service.PaymentActivationService.ActivationResult;
import com.fluxyBackend.service.PlanPricingService;
import com.mercadopago.MercadoPagoConfig;
import com.mercadopago.client.payment.PaymentClient;
import com.mercadopago.client.preference.PreferenceBackUrlsRequest;
import com.mercadopago.client.preference.PreferenceClient;
import com.mercadopago.client.preference.PreferenceItemRequest;
import com.mercadopago.client.preference.PreferenceRequest;
import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.exceptions.MPInvalidWebhookSignatureException;
import com.mercadopago.resources.payment.Payment;
import com.mercadopago.resources.preference.Preference;
import com.mercadopago.webhook.WebhookSignatureValidator;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
@Slf4j
public class MercadoPagoController {

    private final UserRepository userRepository;
    private final PlanPricingService pricingService;
    private final PaymentActivationService paymentActivationService;
    private final EmailService emailService;

    @Value("${mercadopago.access_token}")
    private String accessToken;

    @Value("${mercadopago.webhook_secret:}")
    private String webhookSecret;

    @Value("${app.frontend_url:https://fluxyweb.com}")
    private String frontendUrl;

    @Value("${app.backend_url:http://localhost:8080}")
    private String backendUrl;

    @PostConstruct
    void configureSdk() {
        MercadoPagoConfig.setAccessToken(accessToken);
    }

    @PostMapping("/create-preference")
    public ResponseEntity<Map<String, String>> createPreference(
            @RequestBody Map<String, String> body,
            Authentication authentication) {
        User user = userRepository.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new IllegalArgumentException("Usuario no encontrado"));
        if (user.getCompany() == null) {
            throw new IllegalArgumentException("El usuario no tiene una empresa asociada");
        }

        Plan plan = pricingService.parsePlan(body.getOrDefault("plan", "PRO"));
        int months = pricingService.parseMonths(body.getOrDefault("months", "1"));
        BigDecimal price = pricingService.total(plan, months);

        String planLabel = plan == Plan.PRO ? "Plan Pro" : "Plan Business";
        String description = planLabel + " — " + months + " mes" + (months > 1 ? "es" : "");

        PreferenceItemRequest item = PreferenceItemRequest.builder()
                .title(description)
                .quantity(1)
                .unitPrice(price)
                .currencyId(PlanPricingService.CURRENCY)
                .build();

        PreferenceBackUrlsRequest backUrls = PreferenceBackUrlsRequest.builder()
                .success(frontendUrl + "/dashboard?payment=approved&plan=" + plan.name())
                .failure(frontendUrl + "/dashboard?payment=rejected")
                .pending(frontendUrl + "/dashboard?payment=in_process")
                .build();

        PreferenceRequest preferenceRequest = PreferenceRequest.builder()
                .items(List.of(item))
                .backUrls(backUrls)
                .autoReturn("approved")
                .externalReference(user.getCompany().getId() + "|" + plan.name() + "|" + months)
                .notificationUrl(backendUrl + "/payments/webhook")
                .build();

        try {
            Preference preference = new PreferenceClient().create(preferenceRequest);
            return ResponseEntity.ok(Map.of(
                    "preferenceId", preference.getId(),
                    "initPoint", preference.getInitPoint(),
                    "sandboxUrl", preference.getSandboxInitPoint()
            ));
        } catch (MPException | MPApiException e) {
            log.error("Error creando preferencia de Mercado Pago", e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "No se pudo iniciar el pago"));
        }
    }

    @PostMapping("/webhook")
    public ResponseEntity<Void> webhook(
            @RequestBody(required = false) Map<String, Object> body,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String id,
            @RequestParam(name = "data.id", required = false) String queryDataId,
            @RequestHeader(name = "x-signature", required = false) String signature,
            @RequestHeader(name = "x-request-id", required = false) String requestId) {

        String topic = type != null ? type : bodyValue(body, "type");
        if (!"payment".equals(topic)) {
            return ResponseEntity.ok().build();
        }

        String paymentId = firstNonBlank(queryDataId, nestedDataId(body), id);
        if (paymentId == null) {
            return ResponseEntity.badRequest().build();
        }
        if (webhookSecret == null || webhookSecret.isBlank()) {
            log.error("MERCADOPAGO_WEBHOOK_SECRET no está configurado");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        try {
            WebhookSignatureValidator.validate(signature, requestId, paymentId, webhookSecret);

            Payment payment = new PaymentClient().get(Long.parseLong(paymentId));
            Optional<ActivationResult> result = paymentActivationService.activate(payment);
            result.ifPresent(this::sendActivationEmail);
            return ResponseEntity.ok().build();
        } catch (MPInvalidWebhookSignatureException e) {
            log.warn("Webhook de Mercado Pago con firma inválida");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        } catch (MPException | MPApiException e) {
            log.error("No se pudo consultar el pago {} en Mercado Pago", paymentId, e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        } catch (Exception e) {
            log.error("Error procesando webhook de Mercado Pago para pago {}", paymentId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private void sendActivationEmail(ActivationResult result) {
        if (result.ownerEmail() == null) {
            return;
        }
        emailService.sendPlanActivatedEmail(
                result.ownerEmail(),
                result.ownerName(),
                result.plan().name(),
                result.expiresAt()
        );
        log.info("Plan {} activado para company {}", result.plan(), result.companyId());
    }

    private String nestedDataId(Map<String, Object> body) {
        if (body == null) {
            return null;
        }
        Object data = body.get("data");
        if (data instanceof Map<?, ?> dataMap) {
            Object value = dataMap.get("id");
            return value == null ? null : String.valueOf(value);
        }
        return null;
    }

    private String bodyValue(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) {
            return null;
        }
        return String.valueOf(body.get(key));
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
