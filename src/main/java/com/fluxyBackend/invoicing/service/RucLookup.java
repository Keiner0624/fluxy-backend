package com.fluxyBackend.invoicing.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * Consulta del padrón de contribuyentes en un servicio externo (por ejemplo, APIs Perú / Decolecta).
 * Se configura en el servidor: app.invoicing.ruc_lookup.url (con {ruc}) y app.invoicing.ruc_lookup.token.
 * Sin configurar, no hay verificación real y ningún negocio pasa a producción: Fluxy nunca
 * "supone" que un RUC es válido.
 *
 * Acepta respuestas en camelCase o snake_case (razonSocial / razon_social).
 */
@Slf4j
@Component
public class RucLookup {

    public record RucInfo(String ruc, String businessName, String status, String condition, String address) {}

    /** El servicio no respondió o no está configurado: la verificación queda pendiente, no aprobada. */
    public static class Unavailable extends RuntimeException {
        public Unavailable(String message) {
            super(message);
        }
    }

    private final String urlTemplate;
    private final String token;
    private final JsonMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public RucLookup(@Value("${app.invoicing.ruc_lookup.url:}") String urlTemplate,
                     @Value("${app.invoicing.ruc_lookup.token:}") String token,
                     JsonMapper json) {
        this.urlTemplate = urlTemplate;
        this.token = token;
        this.json = json;
    }

    public boolean configured() {
        return urlTemplate != null && urlTemplate.startsWith("https://") && urlTemplate.contains("{ruc}");
    }

    /** empty: el padrón no tiene ese RUC. */
    public Optional<RucInfo> lookup(String ruc) {
        if (!configured()) throw new Unavailable("La verificación de RUC no está configurada en el servidor.");
        String url = urlTemplate.replace("{ruc}", URLEncoder.encode(ruc, StandardCharsets.UTF_8));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET()
                .header("Accept", "application/json");
        if (token != null && !token.isBlank()) request.header("Authorization", "Bearer " + token);
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404 || response.statusCode() == 422) return Optional.empty();
            if (response.statusCode() >= 300) {
                throw new Unavailable("El servicio de consulta de RUC respondió " + response.statusCode() + ".");
            }
            JsonNode node = json.readTree(response.body());
            if (node.has("data") && node.path("data").isObject()) node = node.path("data");
            String name = first(node, "razonSocial", "razon_social", "nombre");
            if (name == null) return Optional.empty();
            return Optional.of(new RucInfo(ruc, name, upper(first(node, "estado")), upper(first(node, "condicion")),
                    first(node, "direccion", "direccion_completa", "domicilio_fiscal")));
        } catch (IOException e) {
            throw new Unavailable("No se pudo consultar el RUC en este momento.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("Consulta de RUC interrumpida.");
        } catch (RuntimeException e) {
            if (e instanceof Unavailable u) throw u;
            log.warn("Respuesta inesperada del servicio de RUC: {}", e.getMessage());
            throw new Unavailable("El servicio de consulta de RUC devolvió una respuesta inesperada.");
        }
    }

    private static String first(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull() && !value.asString().isBlank()) return value.asString().strip();
        }
        return null;
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(java.util.Locale.ROOT);
    }
}
