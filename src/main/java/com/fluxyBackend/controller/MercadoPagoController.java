package com.fluxyBackend.controller;

import com.fluxyBackend.security.access.Permission;
import com.fluxyBackend.security.access.RequirePermission;

import com.fluxyBackend.entity.Company.Plan;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.repository.UserRepository;
import com.fluxyBackend.service.EmailService;
import com.fluxyBackend.service.PaymentActivationService;
import com.fluxyBackend.service.PaymentActivationService.ActivationResult;
import com.fluxyBackend.service.PlanPricingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.SchemaProperty;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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

@Tag(name = "Pagos", description = "Compra de planes y confirmación de pagos mediante Mercado Pago.")
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
        // Sin límites explícitos, un Mercado Pago lento dejaba colgado el hilo de la petición.
        MercadoPagoConfig.setConnectionTimeout(5_000);
        MercadoPagoConfig.setConnectionRequestTimeout(5_000);
        MercadoPagoConfig.setSocketTimeout(15_000);
        MercadoPagoConfig.setMaxConnections(20);
    }

    @Operation(summary = "Crear una preferencia de pago",
            description = "Crea el checkout para la empresa del usuario autenticado. "
                    + "PRO cuesta S/ 39.00 por mes y BUSINESS S/ 59.00 por mes. "
                    + "La moneda es PEN y el total es precio mensual por meses, sin descuentos. "
                    + "Si se omiten los campos se usa PRO y un mes. Crear la preferencia no activa el plan; "
                    + "la activación depende del webhook y de la verificación del pago.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(type = "object"),
                            schemaProperties = {
                                    @SchemaProperty(name = "plan", schema = @Schema(type = "string",
                                            allowableValues = {"PRO", "BUSINESS"}, defaultValue = "PRO",
                                            description = "Plan de pago; se normalizan espacios y mayúsculas.")),
                                    @SchemaProperty(name = "months", schema = @Schema(type = "string",
                                            defaultValue = "1", example = "3",
                                            description = "Cantidad entera de meses, entre 1 y 12, enviada como texto."))
                            },
                            examples = @ExampleObject(value = "{\"plan\":\"PRO\",\"months\":\"3\"}"))),
            responses = {
                    @ApiResponse(responseCode = "200", description = "Preferencia creada",
                            content = @Content(mediaType = "application/json",
                                    schema = @Schema(type = "object"),
                                    schemaProperties = {
                                            @SchemaProperty(name = "preferenceId", schema = @Schema(type = "string")),
                                            @SchemaProperty(name = "initPoint", schema = @Schema(type = "string", format = "uri")),
                                            @SchemaProperty(name = "sandboxUrl", schema = @Schema(type = "string", format = "uri"))
                                    })),
                    @ApiResponse(responseCode = "400", description = "Plan o duración inválidos", content = @Content),
                    @ApiResponse(responseCode = "403", description = "Se requiere un JWT válido", content = @Content),
                    @ApiResponse(responseCode = "502", description = "No se pudo iniciar el pago",
                            content = @Content(mediaType = "application/json",
                                    examples = @ExampleObject(value = "{\"error\":\"No se pudo iniciar el pago\"}")))
            })
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/create-preference")
    @RequirePermission(Permission.BILLING_MANAGE)
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

    @Operation(summary = "Recibir una notificación de Mercado Pago",
            description = "No requiere JWT. Para eventos payment valida x-signature y x-request-id "
                    + "con el secreto configurado y consulta el pago en Mercado Pago. "
                    + "Los eventos de otros tipos se ignoran con 200. "
                    + "El ID se obtiene de data.id en query, data.id del cuerpo o id en query, en ese orden. "
                    + "La activación verifica el pago y evita procesar el mismo pago dos veces.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = "{\"type\":\"payment\",\"data\":{\"id\":\"123456789\"}}"))),
            responses = {
                    @ApiResponse(responseCode = "200", description = "Notificación procesada o ignorada", content = @Content),
                    @ApiResponse(responseCode = "400", description = "Evento payment sin ID de pago", content = @Content),
                    @ApiResponse(responseCode = "401", description = "Firma del webhook inválida", content = @Content),
                    @ApiResponse(responseCode = "502", description = "Error al consultar el pago en Mercado Pago", content = @Content),
                    @ApiResponse(responseCode = "503", description = "Secreto del webhook no configurado", content = @Content),
                    @ApiResponse(responseCode = "500", description = "Error al procesar la notificación", content = @Content)
            })
    @PostMapping("/webhook")
    public ResponseEntity<Void> webhook(
            @RequestBody(required = false) Map<String, Object> body,
            @Parameter(description = "Tipo de evento; tiene prioridad sobre type del cuerpo.", example = "payment")
            @RequestParam(required = false) String type,
            @Parameter(description = "ID alternativo de pago.", example = "123456789")
            @RequestParam(required = false) String id,
            @Parameter(description = "ID de pago prioritario.", example = "123456789")
            @RequestParam(name = "data.id", required = false) String queryDataId,
            @Parameter(description = "Firma de Mercado Pago; necesaria para validar eventos payment.")
            @RequestHeader(name = "x-signature", required = false) String signature,
            @Parameter(description = "Identificador de solicitud usado para validar la firma de eventos payment.")
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
