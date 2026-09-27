package com.fluxyBackend.invoicing.service;

import com.fluxyBackend.invoicing.enums.DocumentStatus;
import com.fluxyBackend.invoicing.repository.ElectronicDocumentRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Métricas del documento: invoice.issue.duration, invoice.issue (success/failure/rejected),
 * invoice.provider.timeout, email.delivery (success/failure), webhook.invalid_signature y queue.pending.
 * Sin registro de métricas (por ejemplo en algunas pruebas) no hace nada.
 */
@Component
public class InvoicingMetrics {

    private final MeterRegistry registry;

    public InvoicingMetrics(ObjectProvider<MeterRegistry> registry, ElectronicDocumentRepository documents) {
        this.registry = registry.getIfAvailable();
        if (this.registry != null) {
            Gauge.builder("invoicing.queue.pending", documents, d -> d.countByStatusIn(DocumentStatus.WORKABLE))
                    .description("Comprobantes pendientes de enviar o de respuesta").register(this.registry);
        }
    }

    public void issued(String outcome, Duration duration) {
        if (registry == null) return;
        registry.counter("invoicing.invoice.issue", "outcome", outcome).increment();
        registry.timer("invoicing.invoice.issue.duration").record(duration);
    }

    public void providerTimeout(String provider) {
        if (registry != null) registry.counter("invoicing.invoice.provider.timeout", "provider", provider).increment();
    }

    public void email(boolean success) {
        if (registry != null) registry.counter("invoicing.email.delivery", "result", success ? "success" : "failure").increment();
    }

    public void invalidWebhook(String provider) {
        if (registry != null) registry.counter("invoicing.webhook.invalid_signature", "provider", provider).increment();
    }
}
