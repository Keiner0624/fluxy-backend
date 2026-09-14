package com.fluxyBackend.service;

import tools.jackson.databind.json.JsonMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Códigos de verificación por WhatsApp con la Cloud API de Meta.
 *
 * Usa una plantilla de categoría "Autenticación" aprobada en WhatsApp Manager.
 * Mientras falten las variables de entorno el servicio queda desactivado y la
 * verificación del WhatsApp no se exige.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WhatsAppOtpService {

    private final JsonMapper objectMapper;

    @Value("${whatsapp.cloud.token:}")
    private String accessToken;

    @Value("${whatsapp.cloud.phone_number_id:}")
    private String phoneNumberId;

    @Value("${whatsapp.cloud.otp_template:}")
    private String templateName;

    @Value("${whatsapp.cloud.template_language:es}")
    private String templateLanguage;

    /** Las plantillas de autenticación con botón "Copiar código" necesitan el código también en el botón. */
    @Value("${whatsapp.cloud.template_has_copy_button:true}")
    private boolean templateHasCopyButton;

    @Value("${whatsapp.cloud.api_version:v21.0}")
    private String apiVersion;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private final CircuitBreaker breaker = new CircuitBreaker("whatsapp-cloud", 5, Duration.ofMinutes(1));

    public boolean isConfigured() {
        return notBlank(accessToken) && notBlank(phoneNumberId) && notBlank(templateName);
    }

    /** @param phoneDigits número con código de país y sin símbolos: 51987654321. */
    public boolean sendCode(String phoneDigits, String code) {
        if (!isConfigured()) {
            log.warn("WhatsApp Cloud API no configurada: no se envió el código");
            return false;
        }
        if (!breaker.allowRequest()) {
            log.warn("WhatsApp Cloud API en pausa por fallos recientes");
            return false;
        }
        try {
            List<Map<String, Object>> components = new ArrayList<>();
            components.add(Map.of("type", "body",
                    "parameters", List.of(Map.of("type", "text", "text", code))));
            if (templateHasCopyButton) {
                components.add(Map.of("type", "button", "sub_type", "url", "index", "0",
                        "parameters", List.of(Map.of("type", "text", "text", code))));
            }
            Map<String, Object> body = Map.of(
                    "messaging_product", "whatsapp",
                    "to", phoneDigits,
                    "type", "template",
                    "template", Map.of(
                            "name", templateName,
                            "language", Map.of("code", templateLanguage),
                            "components", components));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://graph.facebook.com/" + apiVersion + "/" + phoneNumberId + "/messages"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                breaker.recordSuccess();
                return true;
            }
            if (response.statusCode() >= 500) breaker.recordFailure();
            // El cuerpo de error de Meta no incluye el código enviado: se puede loguear.
            log.error("WhatsApp Cloud API rechazó el mensaje: HTTP {} {}", response.statusCode(),
                    response.body().length() > 300 ? response.body().substring(0, 300) : response.body());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            breaker.recordFailure();
            log.error("No se pudo enviar el código por WhatsApp: {}", e.getMessage());
            return false;
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
