package com.fluxyBackend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Service
public class VercelDomainService {

    private static final Logger log = LoggerFactory.getLogger(VercelDomainService.class);

    @Value("${vercel.token:}")
    private String vercelToken;

    @Value("${vercel.project_id:}")
    private String projectId;

    @Value("${vercel.team_id:}")
    private String teamId;

    // ─── Agregar dominio al proyecto de Vercel ───────────────────────────────
    public boolean addDomain(String domain) {
        if (vercelToken.isBlank() || projectId.isBlank()) {
            log.warn("Vercel token o project ID no configurados");
            return false;
        }

        try {
            String url = "https://api.vercel.com/v9/projects/" + projectId + "/domains"
                    + (teamId.isBlank() ? "" : "?teamId=" + teamId);

            String body = "{\"name\":\"" + domain + "\"}";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + vercelToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            log.info("Vercel addDomain response [{}]: {}", response.statusCode(), response.body());
            return response.statusCode() == 200 || response.statusCode() == 409; // 409 = ya existe

        } catch (Exception e) {
            log.error("Error agregando dominio a Vercel: {}", e.getMessage());
            return false;
        }
    }

    // ─── Verificar estado del dominio ────────────────────────────────────────
    public String getDomainStatus(String domain) {
        if (vercelToken.isBlank() || projectId.isBlank()) return "not_configured";

        try {
            String url = "https://api.vercel.com/v9/projects/" + projectId + "/domains/" + domain
                    + (teamId.isBlank() ? "" : "?teamId=" + teamId);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + vercelToken)
                    .GET()
                    .build();

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            String body = response.body();
            if (body.contains("\"verified\":true")) return "verified";
            if (body.contains("\"verified\":false")) return "pending";
            return "error";

        } catch (Exception e) {
            log.error("Error verificando dominio: {}", e.getMessage());
            return "error";
        }
    }

    // ─── Eliminar dominio del proyecto ───────────────────────────────────────
    public boolean removeDomain(String domain) {
        if (vercelToken.isBlank() || projectId.isBlank()) return false;

        try {
            String url = "https://api.vercel.com/v9/projects/" + projectId + "/domains/" + domain
                    + (teamId.isBlank() ? "" : "?teamId=" + teamId);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + vercelToken)
                    .DELETE()
                    .build();

            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());

            return response.statusCode() == 200;

        } catch (Exception e) {
            log.error("Error eliminando dominio: {}", e.getMessage());
            return false;
        }
    }
}