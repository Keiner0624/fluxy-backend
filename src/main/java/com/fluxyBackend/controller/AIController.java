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

    @Value("${gemini.api.key}")
    private String geminiApiKey;

    @PostMapping("/describe")
    public ResponseEntity<?> generateDescription(
            Authentication authentication,
            @RequestBody Map<String, String> body) {

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

        String prompt = "Eres un experto en copywriting para e-commerce latinoamericano. "
                + "Genera una descripción atractiva y profesional para este producto: "
                + "Nombre: " + name + ". "
                + "Precio: S/ " + (price.isEmpty() ? "no especificado" : price) + ". "
                + "Categoría: " + (category.isEmpty() ? "general" : category) + ". "
                + "Requisitos: máximo 2 oraciones, tono cercano y persuasivo, "
                + "resalta beneficios no características técnicas, sin emojis, en español. "
                + "Responde SOLO con la descripción, sin comillas ni explicaciones.";

        try {
            String escapedPrompt = prompt
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");

            String requestBody = "{"
                    + "\"contents\":[{\"parts\":[{\"text\":\"" + escapedPrompt + "\"}]}],"
                    + "\"generationConfig\":{\"maxOutputTokens\":150,\"temperature\":0.7}"
                    + "}";

            String url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=" + geminiApiKey;

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                System.err.println("Gemini API error " + response.statusCode() + ": " + response.body());
                return ResponseEntity.status(502).body(Map.of("message", "Error al contactar la IA. Intenta de nuevo."));
            }

            String description = extractGeminiText(response.body());

            if (description == null || description.isBlank()) {
                return ResponseEntity.status(500).body(Map.of("message", "No se pudo extraer la descripción."));
            }

            return ResponseEntity.ok(Map.of("description", description));

        } catch (Exception e) {
            System.err.println("Error en AIController: " + e.getMessage());
            return ResponseEntity.status(500).body(Map.of("message", "Error interno: " + e.getMessage()));
        }
    }

    private String extractGeminiText(String json) {
        try {
            String textKey = "\"text\":\"";
            int textIdx = json.indexOf(textKey);
            if (textIdx == -1) return null;

            int start = textIdx + textKey.length();
            StringBuilder sb = new StringBuilder();
            int i = start;
            while (i < json.length()) {
                char c = json.charAt(i);
                if (c == '\\' && i + 1 < json.length()) {
                    char next = json.charAt(i + 1);
                    switch (next) {
                        case '"'  -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case 'n'  -> sb.append(' ');
                        case 'r'  -> {}
                        case 't'  -> sb.append(' ');
                        default   -> sb.append(next);
                    }
                    i += 2;
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                    i++;
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return null;
        }
    }
}