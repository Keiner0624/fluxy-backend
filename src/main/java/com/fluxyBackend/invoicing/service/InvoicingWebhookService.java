package com.fluxyBackend.invoicing.service;

import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.exception.NotFoundException;
import com.fluxyBackend.invoicing.dto.WebhookResult;
import com.fluxyBackend.invoicing.entity.DocumentEvent;
import com.fluxyBackend.invoicing.entity.ElectronicDocument;
import com.fluxyBackend.invoicing.entity.InvoicingWebhookEvent;
import com.fluxyBackend.invoicing.enums.DocumentEventType;
import com.fluxyBackend.invoicing.enums.ProviderCode;
import com.fluxyBackend.invoicing.provider.ProviderResult;
import com.fluxyBackend.invoicing.repository.DocumentEventRepository;
import com.fluxyBackend.invoicing.repository.ElectronicDocumentRepository;
import com.fluxyBackend.invoicing.repository.InvoicingWebhookEventRepository;
import com.fluxyBackend.security.Hashing;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;

/**
 * Avisos de estado de los proveedores (contrato propio de Fluxy para adaptadores que notifican).
 *
 * Cabecera X-Fluxy-Signature: "t=<segundos unix>,v1=<hex HMAC-SHA256 de t + '.' + cuerpo>" con el
 * secreto app.invoicing.webhook_secret. Se rechaza sin firma válida, con más de 5 minutos de
 * diferencia (replay) o con un eventId ya procesado (no se aplica dos veces).
 * Cuerpo: {"eventId","ruc","series","number","status":"ACCEPTED|REJECTED|PROCESSING","message"}.
 */
@Service
public class InvoicingWebhookService {

    static final long TOLERANCE_SECONDS = 300;

    private final String secret;
    private final JsonMapper json;
    private final ElectronicDocumentRepository documents;
    private final InvoicingWebhookEventRepository webhookEvents;
    private final DocumentEventRepository events;
    private final DocumentProcessor processor;
    private final InvoicingMetrics metrics;
    private final TransactionTemplate tx;

    public InvoicingWebhookService(@Value("${app.invoicing.webhook_secret:}") String secret, JsonMapper json,
                                   ElectronicDocumentRepository documents, InvoicingWebhookEventRepository webhookEvents,
                                   DocumentEventRepository events, DocumentProcessor processor, InvoicingMetrics metrics,
                                   TransactionTemplate tx) {
        this.secret = secret;
        this.json = json;
        this.documents = documents;
        this.webhookEvents = webhookEvents;
        this.events = events;
        this.processor = processor;
        this.metrics = metrics;
        this.tx = tx;
    }

    public WebhookResult handle(String providerName, String signature, String body) {
        ProviderCode provider;
        try {
            provider = ProviderCode.valueOf(providerName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("Proveedor desconocido");
        }
        verify(provider, signature, body);

        JsonNode node;
        try {
            node = json.readTree(body);
        } catch (RuntimeException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "WEBHOOK_INVALID", "Cuerpo inválido.");
        }
        String eventId = text(node, "eventId");
        String ruc = text(node, "ruc");
        String series = text(node, "series");
        long number = node.path("number").asLong(0);
        ProviderResult.Outcome outcome;
        try {
            outcome = ProviderResult.Outcome.valueOf(String.valueOf(text(node, "status")).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "WEBHOOK_INVALID", "Estado inválido.");
        }
        if (eventId == null || eventId.length() > 120 || ruc == null || series == null || number <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "WEBHOOK_INVALID", "Faltan datos del comprobante.");
        }
        if (webhookEvents.existsByProviderAndEventId(provider.name(), eventId)) return new WebhookResult(true, null, null);

        ElectronicDocument doc = documents.findFirstByProviderAndIssuerRucAndSeriesAndNumber(provider, ruc, series.toUpperCase(Locale.ROOT), number)
                .orElseThrow(() -> new NotFoundException("Comprobante no encontrado"));
        String message = text(node, "message");
        ProviderResult result = new ProviderResult(outcome, doc.getProviderDocumentId(), message, null, null, null,
                text(node, "hash"), null);
        try {
            tx.executeWithoutResult(s -> {
                webhookEvents.saveAndFlush(new InvoicingWebhookEvent(provider.name(), eventId, doc.getId()));
                events.save(new DocumentEvent(doc.getId(), doc.getCompanyId(), DocumentEventType.WEBHOOK_RECEIVED,
                        "Aviso de " + provider.label() + ": " + outcome.name() + (message != null ? " — " + message : ""), provider.label()));
                processor.apply(doc.getId(), result, provider.label());
            });
        } catch (DataIntegrityViolationException e) {
            // El mismo aviso llegó dos veces a la vez: el otro ya lo aplicó.
            return new WebhookResult(true, doc.getId(), null);
        }
        String status = documents.findById(doc.getId()).map(d -> d.getStatus().name()).orElse(null);
        return new WebhookResult(false, doc.getId(), status);
    }

    private void verify(ProviderCode provider, String header, String body) {
        if (secret == null || secret.isBlank() || header == null) {
            metrics.invalidWebhook(provider.name());
            throw unauthorized();
        }
        String timestamp = null;
        String signature = null;
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) continue;
            if ("t".equals(kv[0])) timestamp = kv[1];
            if ("v1".equals(kv[0])) signature = kv[1];
        }
        long seconds;
        try {
            seconds = Long.parseLong(timestamp);
        } catch (NumberFormatException | NullPointerException e) {
            metrics.invalidWebhook(provider.name());
            throw unauthorized();
        }
        if (Math.abs(Instant.now().getEpochSecond() - seconds) > TOLERANCE_SECONDS) {
            metrics.invalidWebhook(provider.name());
            throw unauthorized();
        }
        String expected = Hashing.hmacSha256(secret.getBytes(StandardCharsets.UTF_8), timestamp + "." + body);
        if (!Hashing.constantTimeEquals(expected, signature)) {
            metrics.invalidWebhook(provider.name());
            throw unauthorized();
        }
    }

    private static BusinessException unauthorized() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "WEBHOOK_SIGNATURE_INVALID", "Firma inválida.");
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        String text = value.asString();
        return text == null || text.isBlank() ? null : text.strip();
    }
}
