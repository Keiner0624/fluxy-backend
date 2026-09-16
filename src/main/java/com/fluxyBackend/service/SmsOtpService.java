package com.fluxyBackend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Códigos de verificación por SMS con la API de mensajes de Twilio.
 *
 * El remitente puede ser un número de Twilio (TWILIO_SMS_FROM, +1…) o un
 * Messaging Service (TWILIO_MESSAGING_SERVICE_SID, MG…), que elige el número
 * y maneja el registro por país. Sin credenciales el servicio queda apagado y
 * no se exige verificar el celular.
 */
@Service
@Slf4j
public class SmsOtpService {

    @Value("${sms.twilio.account_sid:}")
    private String accountSid;

    @Value("${sms.twilio.auth_token:}")
    private String authToken;

    @Value("${sms.twilio.from:}")
    private String from;

    @Value("${sms.twilio.messaging_service_sid:}")
    private String messagingServiceSid;

    @Value("${sms.brand:Fluxy}")
    private String brand;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final CircuitBreaker breaker = new CircuitBreaker("twilio-sms", 5, Duration.ofMinutes(1));

    public boolean isConfigured() {
        return notBlank(accountSid) && notBlank(authToken) && (notBlank(from) || notBlank(messagingServiceSid));
    }

    /** @param phoneDigits número con código de país y sin símbolos: 51987654321. */
    public boolean sendCode(String phoneDigits, String code, long minutes) {
        if (!isConfigured()) {
            log.warn("SMS no configurado: no se envió el código");
            return false;
        }
        if (!breaker.allowRequest()) {
            log.warn("Envío de SMS en pausa por fallos recientes");
            return false;
        }
        try {
            int status = post(accountSid, form(phoneDigits, code, minutes));
            if (status >= 200 && status < 300) {
                breaker.recordSuccess();
                return true;
            }
            // 4xx es configuración o número inválido: no es una caída del proveedor.
            if (status >= 500 || status == 429) breaker.recordFailure();
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            breaker.recordFailure();
            log.error("No se pudo enviar el SMS: {}", e.getMessage());
            return false;
        }
    }

    /** Cuerpo del pedido a Twilio. Paquete visible para las pruebas. */
    Map<String, String> form(String phoneDigits, String code, long minutes) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("To", "+" + phoneDigits.replaceAll("\\D", ""));
        if (notBlank(messagingServiceSid)) form.put("MessagingServiceSid", messagingServiceSid.strip());
        else form.put("From", from.strip());
        // Corto y sin tildes: entra en un solo SMS (GSM-7) y el código queda al principio.
        form.put("Body", code + " es tu codigo de " + brand + ". Vence en " + minutes + " min. No lo compartas con nadie.");
        return form;
    }

    /** Envía el pedido y devuelve el estado HTTP. Protegido para reemplazarlo en pruebas. */
    protected int post(String sid, Map<String, String> form) throws Exception {
        String body = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        String credentials = Base64.getEncoder().encodeToString((sid + ":" + authToken).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.twilio.com/2010-04-01/Accounts/" + URLEncoder.encode(sid, StandardCharsets.UTF_8) + "/Messages.json"))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Basic " + credentials)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            // La respuesta de error de Twilio no repite el cuerpo del mensaje: no expone el código.
            String text = response.body();
            log.error("Twilio rechazó el SMS: HTTP {} {}", response.statusCode(), text.length() > 300 ? text.substring(0, 300) : text);
        }
        return response.statusCode();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
