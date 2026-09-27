package com.fluxyBackend.domain;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * API de dominios de Vercel para el proyecto del frontend (el que sirve las tiendas). Vercel se
 * encarga del certificado HTTPS de cada dominio; acá solo se agrega, consulta, verifica y quita.
 *
 * Variables: VERCEL_TOKEN (token con acceso al proyecto), VERCEL_PROJECT_ID y, si el proyecto
 * está en un equipo, VERCEL_TEAM_ID.
 */
@Slf4j
@Component
public class VercelDomainClient {

    /** Valores de Vercel para cuando su API no sugiere otros. */
    static final String DEFAULT_A_RECORD = "76.76.21.21";
    static final String DEFAULT_CNAME = "cname.vercel-dns.com";

    public record Verification(String type, String domain, String value, String reason) {}

    public record ProjectDomain(String name, String apexName, boolean verified, List<Verification> verification,
                                String redirect) {
        public boolean apex() {
            return name != null && name.equals(apexName);
        }
    }

    /** misconfigured: los DNS todavía no apuntan a Vercel. */
    public record DnsConfig(boolean misconfigured, String aRecord, String cname) {}

    /** Error de Vercel, con el código de su API (p. ej. domain_already_in_use) y un mensaje para mostrar. */
    public static class VercelException extends RuntimeException {
        private final int status;
        private final String code;

        public VercelException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json;
    private final String token;
    private final String projectId;
    private final String teamId;
    private final String baseUrl;

    public VercelDomainClient(JsonMapper json,
                              @Value("${vercel.token:}") String token,
                              @Value("${vercel.project_id:}") String projectId,
                              @Value("${vercel.team_id:}") String teamId,
                              @Value("${vercel.api_url:https://api.vercel.com}") String baseUrl) {
        this.json = json;
        this.token = token == null ? "" : token.strip();
        this.projectId = projectId == null ? "" : projectId.strip();
        this.teamId = teamId == null ? "" : teamId.strip();
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    /** Sin token o proyecto no se pueden conectar dominios (desarrollo local). */
    public boolean configured() {
        return !token.isEmpty() && !projectId.isEmpty();
    }

    /**
     * Agrega el dominio al proyecto. redirectTo (opcional) lo convierte en una redirección 308, como
     * www.mitienda.com → mitienda.com. Si ya estaba en este proyecto, devuelve el existente.
     */
    public ProjectDomain add(String name, String redirectTo) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        if (redirectTo != null) {
            body.put("redirect", redirectTo);
            body.put("redirectStatusCode", 308);
        }
        HttpResponse<String> response = send("POST", "/v10/projects/" + project() + "/domains", json.writeValueAsString(body));
        if (ok(response)) return domain(read(response));
        if (response.statusCode() == 409) {
            // Ya está en este proyecto (reintento) o en otro: solo lo primero es un éxito.
            Optional<ProjectDomain> existing = find(name);
            if (existing.isPresent()) return existing.get();
            throw new VercelException(409, errorCode(response),
                    "Ese dominio ya está conectado a otro sitio en Vercel. Quitalo de allí y volvé a intentarlo.");
        }
        throw failure(response, "No se pudo conectar el dominio.");
    }

    /** El dominio en el proyecto, o vacío si no está. */
    public Optional<ProjectDomain> find(String name) {
        HttpResponse<String> response = send("GET", "/v9/projects/" + project() + "/domains/" + encode(name), null);
        if (response.statusCode() == 404) return Optional.empty();
        if (!ok(response)) throw failure(response, "No se pudo consultar el dominio.");
        return Optional.of(domain(read(response)));
    }

    /** Pide a Vercel que vuelva a buscar el registro TXT de verificación. */
    public Optional<ProjectDomain> verify(String name) {
        HttpResponse<String> response = send("POST", "/v9/projects/" + project() + "/domains/" + encode(name) + "/verify", "{}");
        if (ok(response)) return Optional.of(domain(read(response)));
        // 400 = todavía no encuentra el TXT: no es un error, el dominio sigue sin verificar.
        if (response.statusCode() == 400 || response.statusCode() == 404) return find(name);
        throw failure(response, "No se pudo verificar el dominio.");
    }

    /** Si los DNS del dominio ya apuntan a Vercel, y qué valores recomienda. */
    public DnsConfig config(String name) {
        HttpResponse<String> response = send("GET", "/v6/domains/" + encode(name) + "/config", null);
        if (!ok(response)) throw failure(response, "No se pudo consultar la configuración DNS.");
        JsonNode node = read(response);
        String aRecord = DEFAULT_A_RECORD;
        for (JsonNode option : node.path("recommendedIPv4")) {
            JsonNode values = option.path("value");
            if (option.path("rank").asInt(0) == 1 && values.isArray() && !values.isEmpty()) aRecord = values.get(0).asString(aRecord);
        }
        String cname = DEFAULT_CNAME;
        for (JsonNode option : node.path("recommendedCNAME")) {
            if (option.path("rank").asInt(0) == 1 && !option.path("value").asString("").isBlank()) {
                cname = option.path("value").asString().replaceAll("\\.$", "");
            }
        }
        return new DnsConfig(node.path("misconfigured").asBoolean(true), aRecord, cname);
    }

    /** Quita el dominio del proyecto. Si ya no estaba, no es un error. */
    public void remove(String name) {
        HttpResponse<String> response = send("DELETE", "/v9/projects/" + project() + "/domains/" + encode(name), null);
        if (ok(response) || response.statusCode() == 404) return;
        throw failure(response, "No se pudo quitar el dominio en Vercel.");
    }

    // ─── Apoyo ────────────────────────────────────────────────────────────────

    private HttpResponse<String> send(String method, String path, String body) {
        if (!configured()) {
            throw new VercelException(503, "not_configured", "Los dominios propios no están disponibles en este momento.");
        }
        String url = baseUrl + path + (teamId.isEmpty() ? "" : "?teamId=" + encode(teamId));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
        if (body != null) {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VercelException(503, "interrupted", "No se pudo conectar con Vercel. Probá de nuevo.");
        } catch (Exception e) {
            log.warn("Vercel {} {} falló: {}", method, path, e.getMessage());
            throw new VercelException(503, "unreachable", "No se pudo conectar con Vercel. Probá de nuevo en unos minutos.");
        }
    }

    private ProjectDomain domain(JsonNode node) {
        List<Verification> verification = new ArrayList<>();
        for (JsonNode v : node.path("verification")) {
            verification.add(new Verification(v.path("type").asString(""), v.path("domain").asString(""),
                    v.path("value").asString(""), v.path("reason").asString("")));
        }
        String name = node.path("name").asString(null);
        String apex = node.path("apexName").asString(name);
        String redirect = node.path("redirect").isNull() ? null : node.path("redirect").asString(null);
        return new ProjectDomain(name, apex, node.path("verified").asBoolean(false), verification, redirect);
    }

    private JsonNode read(HttpResponse<String> response) {
        try {
            return json.readTree(response.body() == null || response.body().isBlank() ? "{}" : response.body());
        } catch (Exception e) {
            throw new VercelException(502, "bad_response", "Vercel devolvió una respuesta inesperada.");
        }
    }

    private VercelException failure(HttpResponse<String> response, String fallback) {
        String code = errorCode(response);
        log.warn("Vercel respondió {} ({})", response.statusCode(), code);
        String message = switch (code) {
            case "invalid_domain", "invalid_name" -> "El dominio no es válido.";
            case "forbidden", "not_authorized" -> "Fluxy no tiene permiso para conectar dominios en este momento.";
            case "domain_already_in_use", "domain_taken" ->
                    "Ese dominio ya está conectado a otro sitio en Vercel. Quitalo de allí y volvé a intentarlo.";
            default -> fallback;
        };
        return new VercelException(response.statusCode() >= 500 ? 502 : response.statusCode(), code, message);
    }

    private String errorCode(HttpResponse<String> response) {
        try {
            return read(response).path("error").path("code").asString("unknown");
        } catch (VercelException e) {
            return "unknown";
        }
    }

    private static boolean ok(HttpResponse<String> response) {
        return response.statusCode() >= 200 && response.statusCode() < 300;
    }

    private String project() {
        return encode(projectId);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
