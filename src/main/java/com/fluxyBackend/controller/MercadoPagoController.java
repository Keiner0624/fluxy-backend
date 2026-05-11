package com.fluxyBackend.controller;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Company.Plan;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.repository.CompanyRepository;
import com.fluxyBackend.repository.UserRepository;
import com.fluxyBackend.service.EmailService;
import com.mercadopago.MercadoPagoConfig;
import com.mercadopago.client.preference.*;
import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.resources.preference.Preference;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/payments")
@RequiredArgsConstructor
public class MercadoPagoController {

    private final UserRepository    userRepository;
    private final CompanyRepository companyRepository;
    private final EmailService      emailService;

    @Value("${mercadopago.access_token}")
    private String accessToken;

    @Value("${app.frontend_url:https://fluxyweb.com}")
    private String frontendUrl;

    @Value("${app.backend_url:https://fluxy-backend-production.up.railway.app}")
    private String backendUrl;

    // ─── Crear preferencia de pago ───────────────────────────────────────────
    @PostMapping("/create-preference")
    public ResponseEntity<Map<String, String>> createPreference(
            @RequestBody Map<String, String> body,
            Authentication authentication) {
        try {
            MercadoPagoConfig.setAccessToken(accessToken);

            User user = userRepository.findByEmailIgnoreCase(authentication.getName())
                    .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

            String planStr   = body.getOrDefault("plan", "PRO").toUpperCase();
            int    months    = Integer.parseInt(body.getOrDefault("months", "1"));
            String currency  = body.getOrDefault("currency", "PEN");

            // Precio base por plan y mes
            double basePrice = switch (planStr) {
                case "PRO"      -> 39.0;
                case "BUSINESS" -> 59.0;
                default -> throw new RuntimeException("Plan inválido");
            };

            // Si el frontend envía un precio localizado, usarlo; sino usar el base
            double price;
            if (body.containsKey("price")) {
                try { price = Double.parseDouble(body.get("price")) * months; }
                catch (Exception e) { price = basePrice * months; }
            } else {
                price = basePrice * months;
            }

            String planLabel   = planStr.equals("PRO") ? "Plan Pro" : "Plan Business";
            String description = planLabel + " — " + months + " mes" + (months > 1 ? "es" : "");

            PreferenceItemRequest item = PreferenceItemRequest.builder()
                    .title(description)
                    .quantity(1)
                    .unitPrice(BigDecimal.valueOf(price))
                    .currencyId(currency)
                    .build();

            PreferenceBackUrlsRequest backUrls = PreferenceBackUrlsRequest.builder()
                    .success(frontendUrl + "/dashboard?payment=approved&plan=" + planStr)
                    .failure(frontendUrl + "/dashboard?payment=rejected")
                    .pending(frontendUrl + "/dashboard?payment=in_process")
                    .build();

            PreferenceRequest preferenceRequest = PreferenceRequest.builder()
                    .items(List.of(item))
                    .backUrls(backUrls)
                    .autoReturn("approved")
                    .externalReference(user.getCompany().getId() + "|" + planStr + "|" + months)
                    .notificationUrl(backendUrl + "/payments/webhook")
                    .build();

            PreferenceClient client = new PreferenceClient();
            Preference preference = client.create(preferenceRequest);

            return ResponseEntity.ok(Map.of(
                    "preferenceId", preference.getId(),
                    "initPoint",    preference.getInitPoint(),
                    "sandboxUrl",   preference.getSandboxInitPoint()
            ));

        } catch (MPException | MPApiException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Error al crear preferencia: " + e.getMessage()));
        }
    }

    // ─── Webhook de Mercado Pago ──────────────────────────────────────────────
    @PostMapping("/webhook")
    public ResponseEntity<Void> webhook(
            @RequestBody(required = false) Map<String, Object> body,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String id) {
        try {
            String topic = type != null ? type
                    : (body != null ? String.valueOf(body.getOrDefault("type", "")) : "");

            if (!"payment".equals(topic)) return ResponseEntity.ok().build();

            MercadoPagoConfig.setAccessToken(accessToken);

            String paymentId = id != null ? id
                    : String.valueOf(body.getOrDefault("data", Map.of()).toString());

            if (paymentId == null || paymentId.isBlank()) return ResponseEntity.ok().build();

            com.mercadopago.client.payment.PaymentClient paymentClient =
                    new com.mercadopago.client.payment.PaymentClient();
            com.mercadopago.resources.payment.Payment payment =
                    paymentClient.get(Long.parseLong(paymentId));

            if (!"approved".equals(payment.getStatus())) return ResponseEntity.ok().build();

            String ref = payment.getExternalReference();
            if (ref == null || !ref.contains("|")) return ResponseEntity.ok().build();

            String[] parts    = ref.split("\\|");
            Long    companyId = Long.parseLong(parts[0]);
            String  planStr   = parts[1];
            int     months    = Integer.parseInt(parts[2]);

            // ─── Activar plan ─────────────────────────────────────────────────
            Company company = companyRepository.findById(companyId)
                    .orElseThrow(() -> new RuntimeException("Empresa no encontrada"));

            Plan plan = Plan.valueOf(planStr.toUpperCase());
            LocalDateTime expiresAt = LocalDateTime.now().plusMonths(months);
            company.setPlan(plan);
            company.setPlanActivatedAt(LocalDateTime.now());
            company.setPlanExpiresAt(expiresAt);
            companyRepository.save(company);

            // ─── Enviar email de confirmación ─────────────────────────────────
            User user = userRepository.findByCompanyId(companyId).stream().findFirst().orElse(null);
            if (user != null) {
                emailService.sendPlanActivatedEmail(
                        user.getEmail(),
                        user.getFullName(),
                        planStr,
                        expiresAt
                );
            }

            System.out.println("✅ Plan " + plan + " activado para company " + companyId + ". Email enviado.");

        } catch (Exception e) {
            System.err.println("❌ Error en webhook MP: " + e.getMessage());
        }

        return ResponseEntity.ok().build();
    }
}