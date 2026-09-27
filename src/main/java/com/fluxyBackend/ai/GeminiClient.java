package com.fluxyBackend.ai;

import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.service.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cliente de Google Gemini (API generateContent).
 *
 * La clave va en la cabecera x-goog-api-key, nunca en la URL (las URLs quedan en logs de proxies).
 * El modelo se configura con GEMINI_MODEL: Google retira modelos viejos y así se cambia sin tocar código.
 */
@Slf4j
@Component
public class GeminiClient {

    private final JsonMapper json;
    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    /** Tras 5 fallas seguidas no se llama a Gemini por un minuto. */
    private final CircuitBreaker breaker = new CircuitBreaker("gemini", 5, Duration.ofMinutes(1));

    public GeminiClient(JsonMapper json,
                        @Value("${gemini.api.key:}") String apiKey,
                        @Value("${gemini.model:gemini-2.5-flash}") String model,
                        @Value("${gemini.base_url:https://generativelanguage.googleapis.com}") String baseUrl) {
        this.json = json;
        this.apiKey = apiKey;
        this.model = model == null || model.isBlank() ? "gemini-2.5-flash" : model.strip();
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Genera texto. jsonOutput pide la respuesta como JSON (responseMimeType application/json).
     * Lanza BusinessException con AI_UNAVAILABLE (503) o AI_FAILED (502) si no se pudo.
     */
    public String generate(String instructions, String prompt, int maxTokens, double temperature, boolean jsonOutput) {
        if (!configured()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE",
                    "La generación con IA no está disponible en este momento.");
        }
        if (!breaker.allowRequest()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE",
                    "La IA está con problemas. Probá de nuevo en un minuto.");
        }
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("maxOutputTokens", maxTokens);
        config.put("temperature", temperature);
        if (jsonOutput) config.put("responseMimeType", "application/json");
        // Los modelos 2.5 "piensan" antes de responder y eso consume tokens y tiempo: para textos cortos no hace falta.
        if (model.startsWith("gemini-2.5")) config.put("thinkingConfig", Map.of("thinkingBudget", 0));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", instructions))));
        body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", prompt)))));
        body.put("generationConfig", config);

        String url = baseUrl + "/v1beta/models/" + URLEncoder.encode(model, StandardCharsets.UTF_8) + ":generateContent";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(25))
                .header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            breaker.recordFailure();
            throw failed("La IA tardó demasiado en responder. Probá de nuevo.");
        } catch (IOException e) {
            breaker.recordFailure();
            log.warn("No se pudo conectar con Gemini: {}", e.getMessage());
            throw failed("No se pudo conectar con la IA. Probá de nuevo.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failed("La generación se interrumpió.");
        }

        int status = response.statusCode();
        if (status == 429) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE",
                    "La IA está recibiendo muchas solicitudes. Probá en unos segundos.");
        }
        if (status >= 500) breaker.recordFailure();
        if (status != 200) {
            // 400/403 suelen ser configuración (clave, modelo retirado): queda en el log, no se muestra al cliente.
            log.error("Gemini respondió {} con el modelo {}: {}", status, model, truncate(response.body(), 500));
            throw failed("No se pudo generar el texto. Probá de nuevo.");
        }
        breaker.recordSuccess();

        JsonNode root = json.readTree(response.body());
        JsonNode candidate = root.path("candidates").path(0);
        String finish = candidate.path("finishReason").asString("");
        if ("SAFETY".equals(finish) || "PROHIBITED_CONTENT".equals(finish) || root.path("promptFeedback").has("blockReason")) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_CONTENT, "AI_BLOCKED",
                    "La IA no generó un texto para estos datos. Probá con otra descripción del producto.");
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode part : candidate.path("content").path("parts")) {
            if (part.path("thought").asBoolean(false)) continue;
            text.append(part.path("text").asString(""));
        }
        if (text.toString().isBlank()) throw failed("La IA no devolvió un texto. Probá de nuevo.");
        return text.toString().strip();
    }

    private static BusinessException failed(String message) {
        return new BusinessException(HttpStatus.BAD_GATEWAY, "AI_FAILED", message);
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() > max ? value.substring(0, max) + "…" : value;
    }
}
