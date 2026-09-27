package com.fluxyBackend.invoicing.provider.implementations;

import com.fluxyBackend.invoicing.enums.DocumentType;
import com.fluxyBackend.invoicing.enums.ProviderCode;
import com.fluxyBackend.invoicing.enums.TaxAffectation;
import com.fluxyBackend.invoicing.provider.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Nubefact (OSE/PSE) por su API JSON: cada negocio tiene su propia cuenta, con una ruta y un token.
 * El negocio sigue siendo el emisor; Fluxy solo arma y envía el comprobante.
 *
 * Seguridad: la ruta la escribe el vendedor, así que solo se llama a hosts permitidos
 * (app.invoicing.nubefact.allowed_hosts) y por HTTPS: sin esto el servidor podría usarse para
 * pedir URLs internas. Lo mismo vale para los enlaces de PDF/XML que devuelve el proveedor.
 *
 * Antes de producción: validar este mapeo con la documentación vigente de Nubefact y con una
 * cuenta de pruebas.
 */
@Slf4j
@Component
public class NubefactBillingProvider implements ElectronicBillingProvider {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private final JsonMapper json;
    private final HttpClient http;
    private final Set<String> allowedHosts;
    private final boolean allowLocalHttp;
    private final Duration requestTimeout;

    public NubefactBillingProvider(JsonMapper json,
                                   @Value("${app.invoicing.nubefact.allowed_hosts:nubefact.com}") String allowedHosts,
                                   @Value("${app.invoicing.nubefact.allow_local_http:false}") boolean allowLocalHttp,
                                   @Value("${app.invoicing.provider_timeout_seconds:20}") int timeoutSeconds) {
        this.json = json;
        this.allowedHosts = new HashSet<>();
        for (String host : allowedHosts.split(",")) {
            if (!host.isBlank()) this.allowedHosts.add(host.trim().toLowerCase(Locale.ROOT));
        }
        this.allowLocalHttp = allowLocalHttp;
        this.requestTimeout = Duration.ofSeconds(timeoutSeconds);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public ProviderCode code() {
        return ProviderCode.NUBEFACT;
    }

    @Override
    public Set<DocumentType> supportedTypes() {
        return EnumSet.allOf(DocumentType.class);
    }

    @Override
    public boolean requiresCredentials() {
        return true;
    }

    /** true si Fluxy puede llamar a esta URL. */
    public boolean isAllowedEndpoint(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (allowLocalHttp && "http".equals(uri.getScheme()) && ("localhost".equals(host) || "127.0.0.1".equals(host))) {
                return true;
            }
            if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null) return false;
            return allowedHosts.stream().anyMatch(h -> host.equals(h) || host.endsWith("." + h));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public ProviderResult issue(ProviderContext context, IssueRequest request) {
        Map<String, Object> body = document(request);
        Response response = post(context, body);
        if (response.errors() != null) {
            if (isDuplicate(response)) {
                // Ya estaba registrado (un reintento después de un corte): se consulta su estado.
                return getStatus(context, request);
            }
            if (response.status() >= 500) throw new ProviderException("Nubefact respondió " + response.status(), false);
            if (response.status() == 401 || response.status() == 403) {
                throw new ProviderException("Nubefact rechazó las credenciales del negocio", false);
            }
            return ProviderResult.rejected(response.errors());
        }
        return result(request, response.body());
    }

    @Override
    public ProviderResult getStatus(ProviderContext context, IssueRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operacion", "consultar_comprobante");
        body.put("tipo_de_comprobante", typeCode(request.type()));
        body.put("serie", request.series());
        body.put("numero", request.number());
        Response response = post(context, body);
        if (response.errors() != null) {
            if (response.status() >= 500) throw new ProviderException("Nubefact respondió " + response.status(), false);
            if (response.status() == 401 || response.status() == 403) {
                throw new ProviderException("Nubefact rechazó las credenciales del negocio", false);
            }
            // No existe en Nubefact: nunca llegó; el procesador lo vuelve a enviar.
            throw new ProviderException("Nubefact no encuentra el comprobante: " + response.errors(), false);
        }
        return result(request, response.body());
    }

    @Override
    public ProviderHealth healthCheck(ProviderContext context) {
        if (context.endpoint() == null || context.token() == null) {
            return new ProviderHealth(false, "Falta la ruta o el token de Nubefact.");
        }
        if (!isAllowedEndpoint(context.endpoint())) {
            return new ProviderHealth(false, "La ruta no es una dirección de Nubefact válida (tiene que ser https).");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operacion", "consultar_comprobante");
        body.put("tipo_de_comprobante", 2);
        body.put("serie", "B999");
        body.put("numero", 99999999);
        try {
            Response response = post(context, body);
            if (response.status() == 401 || response.status() == 403 || containsAny(response.errors(), "token", "autoriz")) {
                return new ProviderHealth(false, "Nubefact no aceptó el token. Revisalo en tu cuenta de Nubefact.");
            }
            if (response.status() >= 500) return new ProviderHealth(false, "Nubefact no está respondiendo. Probá en unos minutos.");
            return new ProviderHealth(true, "Conexión con Nubefact correcta.");
        } catch (ProviderException e) {
            return new ProviderHealth(false, "No se pudo conectar con Nubefact: " + e.getMessage());
        }
    }

    @Override
    public byte[] download(ProviderContext context, String storageKey) {
        if (storageKey == null || !isAllowedEndpoint(storageKey)) return null;
        HttpRequest request = HttpRequest.newBuilder(URI.create(storageKey)).timeout(requestTimeout).GET().build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 200 && response.statusCode() < 300) return response.body();
            throw new ProviderException("No se pudo descargar el archivo (HTTP " + response.statusCode() + ")", false);
        } catch (IOException e) {
            throw new ProviderException("No se pudo descargar el archivo de Nubefact", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("Descarga interrumpida", false);
        }
    }

    // ─── Mapeo ───────────────────────────────────────────────────────────────

    Map<String, Object> document(IssueRequest r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operacion", "generar_comprobante");
        body.put("tipo_de_comprobante", typeCode(r.type()));
        body.put("serie", r.series());
        body.put("numero", r.number());
        body.put("sunat_transaction", 1);
        body.put("cliente_tipo_de_documento", r.customerDocumentType().sunatCode());
        body.put("cliente_numero_de_documento", r.customerDocumentNumber() == null ? "-" : r.customerDocumentNumber());
        body.put("cliente_denominacion", r.customerName());
        body.put("cliente_direccion", r.customerAddress() == null ? "" : r.customerAddress());
        body.put("cliente_email", "");
        body.put("fecha_de_emision", r.issueDate().format(DATE));
        body.put("moneda", 1);
        body.put("porcentaje_de_igv", r.taxAffectation() == TaxAffectation.GRAVADO ? 18.00 : 0.00);
        body.put("total_gravada", r.taxAffectation() == TaxAffectation.GRAVADO ? r.subtotal() : "");
        body.put("total_exonerada", r.taxAffectation() == TaxAffectation.EXONERADO ? r.subtotal() : "");
        body.put("total_inafecta", r.taxAffectation() == TaxAffectation.INAFECTO ? r.subtotal() : "");
        body.put("total_igv", r.tax());
        body.put("total", r.total());
        body.put("enviar_automaticamente_a_la_sunat", true);
        // El correo lo manda Fluxy, con su propio seguimiento.
        body.put("enviar_automaticamente_al_cliente", false);
        body.put("codigo_unico", "FLUXY-" + r.issuerRuc() + "-" + r.type().sunatCode() + "-" + r.fullNumber());
        if (r.related() != null) {
            body.put("documento_que_se_modifica_tipo", typeCode(r.related().type()));
            body.put("documento_que_se_modifica_serie", r.related().series());
            body.put("documento_que_se_modifica_numero", r.related().number());
            body.put("tipo_de_nota_de_credito", Integer.parseInt(r.related().reason().sunatCode()));
            body.put("observaciones", r.related().description() == null ? "" : r.related().description());
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (IssueRequest.Item item : r.items()) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("unidad_de_medida", "NIU");
            line.put("codigo", item.code() == null ? "" : item.code());
            line.put("descripcion", item.description());
            line.put("cantidad", item.quantity());
            line.put("valor_unitario", item.unitValue());
            line.put("precio_unitario", item.unitPrice());
            line.put("descuento", "");
            line.put("subtotal", item.subtotal());
            line.put("tipo_de_igv", igvType(r.taxAffectation()));
            line.put("igv", item.tax());
            line.put("total", item.total());
            line.put("anticipo_regularizacion", false);
            items.add(line);
        }
        body.put("items", items);
        return body;
    }

    private ProviderResult result(IssueRequest request, JsonNode body) {
        String id = request.series() + "-" + request.number();
        String description = text(body, "sunat_description");
        String code = text(body, "sunat_responsecode");
        String hash = text(body, "codigo_hash");
        String qr = text(body, "cadena_para_codigo_qr");
        String pdf = text(body, "enlace_del_pdf");
        String xml = text(body, "enlace_del_xml");
        String cdr = text(body, "enlace_del_cdr");
        if (body.path("aceptada_por_sunat").asBoolean(false)) {
            return new ProviderResult(ProviderResult.Outcome.ACCEPTED, id, description, pdf, xml, cdr, hash,
                    qr == null ? SunatQr.text(request, hash) : qr);
        }
        String soapError = text(body, "sunat_soap_error");
        boolean rejected = (code != null && !code.isBlank() && !"0".equals(code)) || (soapError != null && !soapError.isBlank());
        if (rejected) {
            String message = description != null ? description : soapError;
            return new ProviderResult(ProviderResult.Outcome.REJECTED, id, message, pdf, xml, cdr, hash, qr);
        }
        // Registrado en Nubefact, todavía sin respuesta de SUNAT (las boletas van en el resumen diario).
        return new ProviderResult(ProviderResult.Outcome.PROCESSING, id,
                description == null ? "Enviado; esperando la respuesta de SUNAT." : description, pdf, xml, cdr, hash,
                qr == null ? SunatQr.text(request, hash) : qr);
    }

    private static int typeCode(DocumentType type) {
        return switch (type) {
            case FACTURA -> 1;
            case BOLETA -> 2;
            case NOTA_CREDITO -> 3;
        };
    }

    private static int igvType(TaxAffectation affectation) {
        return switch (affectation) {
            case GRAVADO -> 1;
            case EXONERADO -> 8;
            case INAFECTO -> 9;
        };
    }

    private record Response(int status, JsonNode body, String errors) {}

    private Response post(ProviderContext context, Map<String, Object> body) {
        if (context.endpoint() == null || !isAllowedEndpoint(context.endpoint())) {
            throw new ProviderException("La ruta de Nubefact no es válida", false);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(context.endpoint()))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Token token=\"" + context.token() + "\"")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode node;
            try {
                node = json.readTree(response.body() == null || response.body().isBlank() ? "{}" : response.body());
            } catch (RuntimeException e) {
                if (response.statusCode() >= 500) throw new ProviderException("Nubefact respondió " + response.statusCode(), false);
                node = json.readTree("{}");
            }
            String errors = node.hasNonNull("errors") ? node.path("errors").asString() : null;
            if (errors == null && response.statusCode() >= 400) errors = "HTTP " + response.statusCode();
            return new Response(response.statusCode(), node, errors);
        } catch (HttpTimeoutException e) {
            throw new ProviderException("Nubefact no respondió a tiempo", true);
        } catch (IOException e) {
            throw new ProviderException("No se pudo conectar con Nubefact", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("Envío interrumpido", false);
        }
    }

    private static boolean isDuplicate(Response response) {
        return containsAny(response.errors(), "ya existe", "ya fue", "duplicad");
    }

    private static boolean containsAny(String text, String... needles) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        return Arrays.stream(needles).anyMatch(lower::contains);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        String text = value.asString();
        return text == null || text.isBlank() ? null : text;
    }
}
