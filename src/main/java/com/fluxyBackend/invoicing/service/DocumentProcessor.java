package com.fluxyBackend.invoicing.service;

import com.fluxyBackend.entity.Role;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.invoicing.entity.DocumentEvent;
import com.fluxyBackend.invoicing.entity.ElectronicDocument;
import com.fluxyBackend.invoicing.entity.ElectronicDocumentItem;
import com.fluxyBackend.invoicing.enums.*;
import com.fluxyBackend.invoicing.event.DocumentAcceptedEvent;
import com.fluxyBackend.invoicing.provider.*;
import com.fluxyBackend.invoicing.repository.DocumentEventRepository;
import com.fluxyBackend.invoicing.repository.ElectronicDocumentRepository;
import com.fluxyBackend.repository.UserRepository;
import com.fluxyBackend.service.AuditAction;
import com.fluxyBackend.service.AuditService;
import com.fluxyBackend.service.CircuitBreaker;
import com.fluxyBackend.service.EmailService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Envía los comprobantes al proveedor fuera de la petición del usuario.
 *
 * - Cada documento se "toma" con un UPDATE condicional: dos hilos (o dos instancias) nunca lo envían a la vez.
 * - La llamada al proveedor ocurre sin transacción abierta; antes y después se guarda el estado.
 * - Falla técnica: reintentos a los 30 s, 2 min y 10 min; después ERROR y aviso al dueño.
 * - Recibido pero sin respuesta de SUNAT (las boletas van en el resumen diario): se consulta más tarde.
 * - Si el proceso se corta a mitad de camino, el lease vence y el documento se vuelve a tomar; el
 *   proveedor responde por serie y número, así que no se emite dos veces.
 */
@Slf4j
@Service
public class DocumentProcessor {

    static final List<Duration> RETRY_DELAYS = List.of(Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10));
    static final List<Duration> STATUS_DELAYS = List.of(Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofMinutes(30),
            Duration.ofHours(1), Duration.ofHours(3), Duration.ofHours(6));
    static final Duration LEASE = Duration.ofMinutes(5);
    static final Duration STATUS_GIVE_UP = Duration.ofHours(72);

    private final ElectronicDocumentRepository documents;
    private final DocumentEventRepository events;
    private final InvoicingConfigurationService configuration;
    private final BillingProviderFactory providers;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher publisher;
    private final AuditService audit;
    private final InvoicingMetrics metrics;
    private final UserRepository users;
    private final EmailService email;

    public DocumentProcessor(ElectronicDocumentRepository documents, DocumentEventRepository events,
                             InvoicingConfigurationService configuration, BillingProviderFactory providers,
                             TransactionTemplate tx, ApplicationEventPublisher publisher, AuditService audit,
                             InvoicingMetrics metrics, UserRepository users, EmailService email) {
        this.documents = documents;
        this.events = events;
        this.configuration = configuration;
        this.providers = providers;
        this.tx = tx;
        this.publisher = publisher;
        this.audit = audit;
        this.metrics = metrics;
        this.users = users;
        this.email = email;
    }

    /** Lo que venció: envíos nuevos, reintentos y consultas de estado. */
    public int processDue() {
        List<Long> due = documents.findDue(DocumentStatus.WORKABLE, LocalDateTime.now(), PageRequest.of(0, 25));
        int done = 0;
        for (Long id : due) {
            if (process(id)) done++;
        }
        return done;
    }

    /** true si tomó el documento y lo procesó. */
    public boolean process(Long id) {
        LocalDateTime now = LocalDateTime.now();
        Integer claimed = tx.execute(s -> documents.claim(id, DocumentStatus.WORKABLE, now, now.plus(LEASE)));
        if (claimed == null || claimed == 0) return false;

        Work work = tx.execute(s -> load(id));
        if (work == null) return false;

        // El permiso se vuelve a comprobar antes de cada envío.
        InvoicingConfigurationService.Authorization auth;
        try {
            auth = tx.execute(s -> configuration.authorize(work.companyId(), work.type(), work.relatedType()));
            if (auth.config().getProvider() != work.provider()) {
                throw new BusinessException("Cambió el proveedor de facturación después de registrar este comprobante.");
            }
        } catch (BusinessException e) {
            tx.executeWithoutResult(s -> stop(id, "No se envió: " + e.getMessage()));
            return true;
        }

        ElectronicBillingProvider provider = providers.get(work.provider());
        CircuitBreaker breaker = providers.breaker(work.provider());
        if (!breaker.allowRequest()) {
            tx.executeWithoutResult(s -> failure(id, work.statusCheck(), "El proveedor está fallando: se reintenta en unos minutos."));
            return true;
        }
        ProviderContext context = configuration.context(auth.config());
        long started = System.nanoTime();
        ProviderResult result;
        try {
            if (!work.statusCheck()) {
                tx.executeWithoutResult(s -> event(id, work.companyId(), DocumentEventType.SENT_TO_PROVIDER,
                        "Enviado a " + work.provider().label() + " (intento " + work.attempts() + ").", "Fluxy"));
            }
            result = work.statusCheck() ? provider.getStatus(context, work.request()) : provider.issue(context, work.request());
            breaker.recordSuccess();
        } catch (ProviderException e) {
            breaker.recordFailure();
            if (e.isTimeout()) metrics.providerTimeout(work.provider().name());
            metrics.issued("failure", Duration.ofNanos(System.nanoTime() - started));
            tx.executeWithoutResult(s -> failure(id, work.statusCheck(), e.getMessage()));
            return true;
        } catch (RuntimeException e) {
            log.error("Error inesperado enviando el comprobante {}", id, e);
            tx.executeWithoutResult(s -> failure(id, work.statusCheck(), "Error inesperado al enviar."));
            return true;
        }
        metrics.issued(result.outcome().name().toLowerCase(), Duration.ofNanos(System.nanoTime() - started));
        tx.executeWithoutResult(s -> apply(id, result, "Fluxy"));
        return true;
    }

    /**
     * Aplica una respuesta del proveedor (envío, consulta o webhook). Idempotente: sobre un documento
     * ya aceptado o rechazado no cambia nada.
     */
    public void apply(Long id, ProviderResult result, String actor) {
        ElectronicDocument doc = documents.findById(id).orElse(null);
        if (doc == null || doc.getStatus().isFinal() || doc.getStatus() == DocumentStatus.CANCEL_PENDING) return;
        LocalDateTime now = LocalDateTime.now();
        if (result.providerDocumentId() != null) doc.setProviderDocumentId(result.providerDocumentId());
        if (result.pdfRef() != null) doc.setPdfStorageKey(result.pdfRef());
        if (result.xmlRef() != null) doc.setXmlStorageKey(result.xmlRef());
        if (result.cdrRef() != null) doc.setCdrStorageKey(result.cdrRef());
        if (result.hash() != null) doc.setHashCode(truncate(result.hash(), 120));
        if (result.qrText() != null) doc.setQrText(truncate(result.qrText(), 600));
        doc.setProviderMessage(truncate(result.message(), 600));
        switch (result.outcome()) {
            case ACCEPTED -> {
                doc.setStatus(DocumentStatus.ACCEPTED);
                doc.setAcceptedAt(now);
                doc.setNextAttemptAt(null);
                doc.setLastError(null);
                var config = configuration.getOrCreate(doc.getCompanyId());
                if (config.isEmailEnabled() && doc.getCustomerEmail() != null) {
                    doc.setEmailStatus(EmailStatus.PENDING);
                    doc.setEmailAttempts(0);
                    doc.setEmailNextAttemptAt(now);
                }
                documents.save(doc);
                event(doc.getId(), doc.getCompanyId(), DocumentEventType.ACCEPTED,
                        result.message() != null ? result.message() : "Aceptado.", actor);
                audit.record(doc.getCompanyId(), null, AuditAction.INVOICE_ACCEPTED, "ELECTRONIC_DOCUMENT", doc.getId(),
                        Map.of("number", doc.fullNumber()));
                if (doc.getType() == DocumentType.NOTA_CREDITO) settleOriginal(doc, true, actor);
                publisher.publishEvent(new DocumentAcceptedEvent(doc.getId(), doc.getCompanyId()));
            }
            case REJECTED -> {
                doc.setStatus(DocumentStatus.REJECTED);
                doc.setRejectedAt(now);
                doc.setNextAttemptAt(null);
                documents.save(doc);
                event(doc.getId(), doc.getCompanyId(), DocumentEventType.REJECTED,
                        result.message() != null ? result.message() : "Rechazado.", actor);
                audit.record(doc.getCompanyId(), null, AuditAction.INVOICE_REJECTED, "ELECTRONIC_DOCUMENT", doc.getId(),
                        Map.of("number", doc.fullNumber(), "reason", String.valueOf(truncate(result.message(), 200))));
                if (doc.getType() == DocumentType.NOTA_CREDITO) settleOriginal(doc, false, actor);
            }
            case PROCESSING -> {
                doc.setStatus(DocumentStatus.PROCESSING);
                if (doc.getIssuedAt().plus(STATUS_GIVE_UP).isBefore(now)) {
                    doc.setStatus(DocumentStatus.ERROR);
                    doc.setNextAttemptAt(null);
                    doc.setLastError("Sin respuesta de SUNAT después de 72 horas: revisalo en tu proveedor.");
                    documents.save(doc);
                    event(doc.getId(), doc.getCompanyId(), DocumentEventType.ERROR, doc.getLastError(), actor);
                    return;
                }
                Duration delay = STATUS_DELAYS.get(Math.min(Math.max(doc.getAttempts() - 1, 0), STATUS_DELAYS.size() - 1));
                doc.setNextAttemptAt(now.plus(delay));
                documents.save(doc);
                event(doc.getId(), doc.getCompanyId(), DocumentEventType.STATUS_CHECKED,
                        (result.message() != null ? result.message() : "Esperando respuesta de SUNAT.")
                                + " Se consulta de nuevo en " + human(delay) + ".", actor);
            }
        }
    }

    /** Nota de crédito resuelta: el original queda anulado o vuelve a aceptado. */
    private void settleOriginal(ElectronicDocument note, boolean accepted, String actor) {
        if (note.getRelatedDocumentId() == null) return;
        documents.findByIdAndCompanyId(note.getRelatedDocumentId(), note.getCompanyId()).ifPresent(original -> {
            if (accepted) {
                original.setStatus(DocumentStatus.CANCELLED);
                documents.save(original);
                event(original.getId(), original.getCompanyId(), DocumentEventType.CANCELLED,
                        "Anulado con la nota de crédito " + note.fullNumber() + ".", actor);
            } else {
                original.setStatus(DocumentStatus.ACCEPTED);
                original.setCreditNoteId(null);
                documents.save(original);
                event(original.getId(), original.getCompanyId(), DocumentEventType.CANCEL_REVERTED,
                        "La nota de crédito " + note.fullNumber() + " fue rechazada: el comprobante sigue vigente.", actor);
            }
        });
    }

    private void failure(Long id, boolean statusCheck, String message) {
        ElectronicDocument doc = documents.findById(id).orElse(null);
        if (doc == null) return;
        LocalDateTime now = LocalDateTime.now();
        doc.setLastError(truncate(message, 600));
        if (statusCheck) {
            // Ya fue recibido: se sigue consultando con la misma cadencia.
            doc.setStatus(DocumentStatus.PROCESSING);
            doc.setNextAttemptAt(now.plus(STATUS_DELAYS.get(Math.min(Math.max(doc.getAttempts() - 1, 0), STATUS_DELAYS.size() - 1))));
            documents.save(doc);
            event(id, doc.getCompanyId(), DocumentEventType.RETRY_SCHEDULED, "No se pudo consultar el estado: " + message, "Fluxy");
            return;
        }
        doc.setStatus(DocumentStatus.ERROR);
        if (doc.getAttempts() <= RETRY_DELAYS.size()) {
            Duration delay = RETRY_DELAYS.get(doc.getAttempts() - 1);
            doc.setNextAttemptAt(now.plus(delay));
            documents.save(doc);
            event(id, doc.getCompanyId(), DocumentEventType.RETRY_SCHEDULED, message + " Se reintenta en " + human(delay) + ".", "Fluxy");
            return;
        }
        doc.setNextAttemptAt(null);
        documents.save(doc);
        event(id, doc.getCompanyId(), DocumentEventType.ERROR, "Se agotaron los reintentos: " + message, "Fluxy");
        audit.record(doc.getCompanyId(), null, AuditAction.INVOICE_FAILED, "ELECTRONIC_DOCUMENT", doc.getId(),
                Map.of("number", doc.fullNumber(), "reason", String.valueOf(truncate(message, 200))));
        alertOwner(doc);
    }

    /** No se puede enviar por configuración (suspendida, sin permiso): queda en ERROR para reintentar a mano. */
    private void stop(Long id, String message) {
        ElectronicDocument doc = documents.findById(id).orElse(null);
        if (doc == null) return;
        doc.setStatus(DocumentStatus.ERROR);
        doc.setNextAttemptAt(null);
        doc.setLastError(truncate(message, 600));
        documents.save(doc);
        event(id, doc.getCompanyId(), DocumentEventType.ERROR, message, "Fluxy");
    }

    private void alertOwner(ElectronicDocument doc) {
        try {
            User owner = users.findFirstByCompanyIdAndRoleOrderByIdAsc(doc.getCompanyId(), Role.BUSINESS_OWNER).orElse(null);
            if (owner != null) {
                email.sendBillingNotice(owner.getEmail(), owner.getFullName(), "No se pudo emitir " + doc.fullNumber(),
                        "El comprobante " + doc.fullNumber() + " no se pudo enviar al proveedor después de varios intentos. "
                                + "Revisá la conexión en Comprobantes y reintentalo.");
            }
        } catch (RuntimeException e) {
            log.warn("No se pudo avisar la falla del comprobante {}: {}", doc.getId(), e.getMessage());
        }
    }

    private void event(Long documentId, Long companyId, DocumentEventType type, String message, String actor) {
        events.save(new DocumentEvent(documentId, companyId, type, message, actor));
    }

    private record Work(Long companyId, DocumentType type, DocumentType relatedType, ProviderCode provider,
                        boolean statusCheck, int attempts, IssueRequest request) {}

    private Work load(Long id) {
        ElectronicDocument doc = documents.findById(id).orElse(null);
        if (doc == null) return null;
        ElectronicDocument related = doc.getRelatedDocumentId() == null ? null
                : documents.findByIdAndCompanyId(doc.getRelatedDocumentId(), doc.getCompanyId()).orElse(null);
        return new Work(doc.getCompanyId(), doc.getType(), related == null ? null : related.getType(), doc.getProvider(),
                doc.getProviderDocumentId() != null, doc.getAttempts(), toRequest(doc, related));
    }

    static IssueRequest toRequest(ElectronicDocument doc, ElectronicDocument related) {
        List<IssueRequest.Item> items = doc.getItems().stream()
                .map((ElectronicDocumentItem i) -> new IssueRequest.Item(i.getSku(), i.getDescription(), i.getQuantity(),
                        i.getUnitValue(), i.getUnitPrice(), i.getSubtotal(), i.getTax(), i.getTotal()))
                .toList();
        IssueRequest.Related rel = related == null ? null : new IssueRequest.Related(related.getType(), related.getSeries(),
                related.getNumber(), doc.getCreditReason(), doc.getCreditDescription());
        return new IssueRequest(doc.getType(), doc.getSeries(), doc.getNumber(), DocumentRenderer.issueDate(doc),
                doc.getIssuerRuc(), doc.getIssuerName(), doc.getCustomerDocumentType(), doc.getCustomerDocumentNumber(),
                doc.getCustomerName(), doc.getCustomerAddress(), doc.getCustomerEmail(), doc.getTaxAffectation(),
                doc.getSubtotal(), doc.getTax(), doc.getTotal(), doc.getCurrency(), items, rel);
    }

    private static String human(Duration d) {
        if (d.toMinutes() < 1) return d.toSeconds() + " s";
        if (d.toHours() < 1) return d.toMinutes() + " min";
        return d.toHours() + " h";
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() > max ? value.substring(0, max) : value;
    }
}
