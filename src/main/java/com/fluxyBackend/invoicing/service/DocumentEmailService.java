package com.fluxyBackend.invoicing.service;

import com.fluxyBackend.invoicing.entity.DocumentEvent;
import com.fluxyBackend.invoicing.entity.ElectronicDocument;
import com.fluxyBackend.invoicing.enums.DocumentEventType;
import com.fluxyBackend.invoicing.enums.EmailStatus;
import com.fluxyBackend.invoicing.enums.Environment;
import com.fluxyBackend.invoicing.repository.DocumentEventRepository;
import com.fluxyBackend.invoicing.repository.ElectronicDocumentRepository;
import com.fluxyBackend.invoicing.repository.InvoicingConfigurationRepository;
import com.fluxyBackend.service.EmailService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Correo del comprobante al cliente: PDF y/o XML adjuntos y un enlace seguro. Estado propio
 * (PENDING → SENT / FAILED) y reintentos; reenviar nunca vuelve a emitir.
 */
@Slf4j
@Service
public class DocumentEmailService {

    static final List<Duration> RETRY_DELAYS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30));
    static final Duration LEASE = Duration.ofMinutes(5);

    private final ElectronicDocumentRepository documents;
    private final DocumentEventRepository events;
    private final InvoicingConfigurationRepository configurations;
    private final ElectronicDocumentService documentService;
    private final DocumentFileService files;
    private final EmailService email;
    private final InvoicingMetrics metrics;
    private final TransactionTemplate tx;

    public DocumentEmailService(ElectronicDocumentRepository documents, DocumentEventRepository events,
                                InvoicingConfigurationRepository configurations, ElectronicDocumentService documentService,
                                DocumentFileService files, EmailService email, InvoicingMetrics metrics, TransactionTemplate tx) {
        this.documents = documents;
        this.events = events;
        this.configurations = configurations;
        this.documentService = documentService;
        this.files = files;
        this.email = email;
        this.metrics = metrics;
        this.tx = tx;
    }

    public int processDue() {
        int sent = 0;
        for (Long id : documents.findEmailDue(EmailStatus.PENDING, LocalDateTime.now(), PageRequest.of(0, 25))) {
            if (send(id)) sent++;
        }
        return sent;
    }

    public boolean send(Long id) {
        LocalDateTime now = LocalDateTime.now();
        Integer claimed = tx.execute(s -> documents.claimEmail(id, now, now.plus(LEASE)));
        if (claimed == null || claimed == 0) return false;

        Message message = tx.execute(s -> build(id));
        if (message == null) return false;
        boolean ok;
        try {
            ok = email.sendWithAttachments(message.to(), message.name(), message.subject(), message.html(), message.attachments());
        } catch (RuntimeException e) {
            log.warn("No se pudo enviar el comprobante {} por correo: {}", id, e.getMessage());
            ok = false;
        }
        metrics.email(ok);
        boolean delivered = ok;
        tx.executeWithoutResult(s -> result(id, delivered));
        return ok;
    }

    private record Message(String to, String name, String subject, String html, List<EmailService.Attachment> attachments) {}

    private Message build(Long id) {
        ElectronicDocument doc = documents.findById(id).orElse(null);
        if (doc == null || doc.getCustomerEmail() == null) return null;
        doc.getItems().size();
        var config = configurations.findByCompanyId(doc.getCompanyId()).orElse(null);
        ElectronicDocument related = documentService.loadRelated(doc);
        String link = documentService.publicUrl(doc);
        List<EmailService.Attachment> attachments = new ArrayList<>();
        try {
            if (config == null || config.isAttachPdf()) {
                DocumentFileService.File pdf = files.pdf(doc, related, link);
                attachments.add(new EmailService.Attachment(pdf.fileName(), pdf.contentType(), pdf.content()));
            }
            if (config != null && config.isAttachXml()) {
                DocumentFileService.File xml = files.xml(doc, related);
                attachments.add(new EmailService.Attachment(xml.fileName(), xml.contentType(), xml.content()));
            }
        } catch (RuntimeException e) {
            // Sin adjuntos igual sale el enlace seguro.
            log.warn("Adjuntos no disponibles para el comprobante {}: {}", id, e.getMessage());
        }
        String issuer = doc.getIssuerTradeName() != null ? doc.getIssuerTradeName() : doc.getIssuerName();
        String subject = doc.getType().label() + " " + doc.fullNumber() + " — " + issuer;
        String html = EmailService.documentHtml(issuer, doc.getType().label(), doc.fullNumber(),
                "S/ " + doc.getTotal().toPlainString(), link, doc.getEnvironment() == Environment.TEST);
        return new Message(doc.getCustomerEmail(), doc.getCustomerName(), subject, html, attachments);
    }

    private void result(Long id, boolean ok) {
        ElectronicDocument doc = documents.findById(id).orElse(null);
        if (doc == null) return;
        if (ok) {
            doc.setEmailStatus(EmailStatus.SENT);
            doc.setEmailSentAt(LocalDateTime.now());
            doc.setEmailNextAttemptAt(null);
            events.save(new DocumentEvent(id, doc.getCompanyId(), DocumentEventType.EMAIL_SENT, "Correo enviado al cliente.", "Fluxy"));
        } else if (doc.getEmailAttempts() <= RETRY_DELAYS.size()) {
            doc.setEmailNextAttemptAt(LocalDateTime.now().plus(RETRY_DELAYS.get(doc.getEmailAttempts() - 1)));
        } else {
            doc.setEmailStatus(EmailStatus.FAILED);
            doc.setEmailNextAttemptAt(null);
            events.save(new DocumentEvent(id, doc.getCompanyId(), DocumentEventType.EMAIL_FAILED,
                    "No se pudo enviar el correo después de varios intentos. Podés reenviarlo desde el comprobante.", "Fluxy"));
        }
        documents.save(doc);
    }
}
