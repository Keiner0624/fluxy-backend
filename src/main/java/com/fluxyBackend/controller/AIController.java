package com.fluxyBackend.controller;

import com.fluxyBackend.entity.Company.Plan;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class AIController {

    private final UserRepository userRepository;

    @Value("${anthropic.api.key}")
    private String anthropicApiKey;

    /**
     * POST /ai/describe
     * Exclusivo para plan BUSINESS.
     * Body: { "name": "...", "price": "...", "category": "..." }
     */
    @PostMapping("/describe")
    public ResponseEntity<?> generateDescription(
            Authentication authentication,
            @RequestBody Map<String, String> body) {

        // Verificar plan BUSINESS
        User user = userRepository.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

        Plan plan = user.getCompany() != null && user.getCompany().getPlan() != null
                ? user.getCompany().getPlan() : Plan.FREE;

        if (plan != Plan.BUSINESS) {
            return ResponseEntity.status(403).body(
                    Map.of("message", "El generador de IA es exclusivo del plan Business.")
            );
        }

        String name     = body.getOrDefault("name",     "").trim();
        String price    = body.getOrDefault("price",    "").trim();
        String category = body.getOrDefault("category", "").trim();

        if (name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "El nombre del producto es obligatorio."));
        }

        String prompt = """
                Eres un experto en copywriting para e-commerce latinoamericano.
                Genera una descripción atractiva y profesional para este producto:

                - Nombre: %s
                - Precio: S/ %s
                - Categoría: %s

                Requisitos:
                - Máximo 2 oraciones
                - Tono cercano y persuasivo
                - Resalta beneficios, no características técnicas
                - Sin emojis
                - En español

                Responde SOLO con la descripción, sin comillas ni explicaciones.
                """.formatted(name, price.isEmpty() ? "no especificado" : price, category.isEmpty() ? "general" : category);

        try {
            String requestBody = """
                    {
                      "model": "claude-sonnet-4-20250514",
                      "max_tokens": 150,
                      "messages": [{"role": "user", "content": "%s"}]
                    }
                    """.formatted(prompt.replace("\"", "\\\"").replace("\n", "\\n"));

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.anthropic.com/v1/messages"))
                    .header("Content-Type",      "application/json")
                    .header("x-api-key",         anthropicApiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return ResponseEntity.status(502).body(Map.of("message", "Error al contactar la IA. Intenta de nuevo."));
            }

            // Extraer texto del JSON de respuesta
            String responseBody = response.body();
            int textStart = responseBody.indexOf("\"text\":\"") + 8;
            int textEnd   = responseBody.indexOf("\"", textStart);
            String description = responseBody.substring(textStart, textEnd)
                    .replace("\\n", " ")
                    .replace("\\\"", "\"")
                    .trim();

            return ResponseEntity.ok(Map.of("description", description));

        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("message", "Error interno: " + e.getMessage()));
        }
    }
}