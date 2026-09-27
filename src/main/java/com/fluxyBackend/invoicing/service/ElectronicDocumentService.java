package com.fluxyBackend.invoicing.service;

import com.fluxyBackend.DTOs.PageResponse;
import com.fluxyBackend.entity.Order;
import com.fluxyBackend.entity.OrderItem;
import com.fluxyBackend.entity.OrderStatus;
import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.exception.NotFoundException;
import com.fluxyBackend.invoicing.dto.CreateDocumentRequest;
import com.fluxyBackend.invoicing.dto.CreditNoteRequest;
import com.fluxyBackend.invoicing.dto.DocumentDetail;
import com.fluxyBackend.invoicing.dto.DocumentRow;
import com.fluxyBackend.invoicing.dto.PublicDocumentView;
import com.fluxyBackend.invoicing.dto.ResendEmailRequest;
import com.fluxyBackend.invoicing.entity.DocumentEvent;
import com.fluxyBackend.invoicing.entity.ElectronicDocument;
import com.fluxyBackend.invoicing.entity.ElectronicDocumentItem;
import com.fluxyBackend.invoicing.enums.*;
import com.fluxyBackend.invoicing.event.DocumentCreatedEvent;
import com.fluxyBackend.invoicing.event.EmailRequestedEvent;
import com.fluxyBackend.invoicing.repository.DocumentEventRepository;
import com.fluxyBackend.invoicing.repository.ElectronicDocumentRepository;
import com.fluxyBackend.invoicing.validation.Receiver;
import com.fluxyBackend.invoicing.validation.TaxIdValidator;
import com.fluxyBackend.repository.OrderRepository;
import com.fluxyBackend.security.Hashing;
import com.fluxyBackend.security.access.Member;
import com.fluxyBackend.service.AuditAction;
import com.fluxyBackend.service.AuditService;
import com.fluxyBackend.service.BusinessClock;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Emisión y consulta de comprobantes. Crear un comprobante solo lo registra (PENDING) y responde
 * enseguida; el envío al proveedor, el PDF y el correo siguen fuera de la petición (DocumentProcessor).
 */
@Service
@RequiredArgsConstructor
public class ElectronicDocumentService {

    public static final String ALREADY_EXISTS = "DOCUMENT_ALREADY_EXISTS";
    public static final String AUTOMATIC_ACTOR = "Emisión automática";

    private final ElectronicDocumentRepository documents;
    private final DocumentEventRepository events;
    private final OrderRepository orders;
    private final InvoicingConfigurationService configuration;
    private final DocumentNumberService numbers;
    private final ApplicationEventPublisher publisher;
    private final AuditService audit;
    private final BusinessClock clock;

    @Value("${app.frontend_url:http://localhost:5173}")
    private String frontendUrl;

    // ─── Emisión ─────────────────────────────────────────────────────────────

    @Transactional
    public DocumentDetail createFromOrder(Member member, CreateDocumentRequest request) {
        ElectronicDocument doc = create(member.companyId(), request, member.displayName());
        audit.record(member, AuditAction.INVOICE_CREATED, "ELECTRONIC_DOCUMENT", doc.getId(),
                Map.of("number", doc.fullNumber(), "type", doc.getType().name(), "orderId", String.valueOf(doc.getOrderId())));
        return detail(doc);
    }

    /**
     * Emisión automática (pago confirmado o pedido entregado). Usa el comprobante que pidió el cliente
     * al comprar; si no pidió, boleta a su nombre. empty si el pedido ya tiene comprobante.
     */
    @Transactional
    public Optional<Long> createAutomatic(Long companyId, Long orderId) {
        if (!documents.findActiveForOrder(companyId, orderId, DocumentStatus.BLOCKING).isEmpty()) return Optional.empty();
        Order order = orders.findByIdAndCompanyId(orderId, companyId).orElseThrow(() -> new NotFoundException("Pedido no encontrado"));
        String type = order.getInvoiceType() == null ? DocumentType.BOLETA.name() : order.getInvoiceType();
        ElectronicDocument doc = create(companyId, new CreateDocumentRequest(orderId, type, null, null, null, null, null, null), AUTOMATIC_ACTOR);
        audit.record(companyId, null, AuditAction.INVOICE_CREATED, "ELECTRONIC_DOCUMENT", doc.getId(),
                Map.of("number", doc.fullNumber(), "type", doc.getType().name(), "orderId", String.valueOf(orderId), "by", "AUTOMATIC"));
        return Optional.of(doc.getId());
    }

    private ElectronicDocument create(Long companyId, CreateDocumentRequest request, String actor) {
        if (request.orderId() == null) throw new BusinessException("Indicá el pedido del comprobante.");
        DocumentType type = parseSaleType(request.type());
        // Permiso reconstruido desde el servidor: plan, configuración activa y perfil fiscal.
        InvoicingConfigurationService.Authorization auth = configuration.authorize(companyId, type, null);

        Order order = orders.findByIdAndCompanyIdForUpdate(request.orderId(), companyId)
                .orElseThrow(() -> new NotFoundException("Pedido no encontrado"));
        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new BusinessException(HttpStatus.CONFLICT, "ORDER_CANCELLED", "El pedido está cancelado: no se emite comprobante.");
        }
        List<ElectronicDocument> active = documents.findActiveForOrder(companyId, order.getId(), DocumentStatus.BLOCKING);
        if (!active.isEmpty()) {
            throw new BusinessException(HttpStatus.CONFLICT, ALREADY_EXISTS, "El pedido ya tiene un comprobante asociado.",
                    Map.of("documentId", active.get(0).getId(), "number", active.get(0).fullNumber()));
        }
        if (order.getItems() == null || order.getItems().isEmpty()) throw new BusinessException("El pedido no tiene productos.");

        List<DocumentCalculator.Input> inputs = new ArrayList<>();
        for (OrderItem item : order.getItems()) {
            String description = item.getProdcut() == null ? "Producto" : item.getProdcut().getName();
            inputs.add(new DocumentCalculator.Input(item.getProdcut() == null ? null : item.getProdcut().getId(),
                    item.getProdcut() == null ? null : item.getProdcut().getSku(), description,
                    BigDecimal.valueOf(item.getQuantity()), DocumentCalculator.money(item.getSubTotal() == null ? 0 : item.getSubTotal())));
        }
        BigDecimal orderTotal = DocumentCalculator.money(order.getTotal() == null ? 0 : order.getTotal());
        if (orderTotal.signum() <= 0) throw new BusinessException("El pedido no tiene importe para facturar.");
        DocumentCalculator.Result amounts = DocumentCalculator.calculate(inputs, orderTotal, auth.config().getTaxAffectation());

        // Datos del cliente: los enviados ahora, o los que dejó al comprar.
        boolean sameType = type.name().equals(order.getInvoiceType());
        Receiver receiver = Receiver.validate(type,
                first(request.customerDocumentType(), sameType ? order.getBuyerDocumentType() : null),
                first(request.customerDocumentNumber(), sameType ? order.getBuyerDocumentNumber() : null),
                first(request.customerName(), sameType ? order.getBuyerLegalName() : null,
                        type == DocumentType.BOLETA ? order.getCustomerName() : null),
                first(request.customerAddress(), sameType ? order.getBuyerFiscalAddress() : null),
                first(request.customerEmail(), order.getBuyerEmail(), order.getCustomer() == null ? null : order.getCustomer().getEmail()),
                amounts.total());

        Environment environment = auth.config().getProvider().environment();
        DocumentNumberService.Reserved reserved = numbers.next(companyId, environment, type, type.seriesPrefix(), blankToNull(request.series()));

        ElectronicDocument doc = new ElectronicDocument();
        doc.setCompanyId(companyId);
        doc.setOrderId(order.getId());
        doc.setCustomerId(order.getCustomerId());
        doc.setType(type);
        doc.setEnvironment(environment);
        doc.setProvider(auth.config().getProvider());
        doc.setSeries(reserved.series());
        doc.setNumber(reserved.number());
        issuer(doc, auth);
        receiver(doc, receiver);
        doc.setTaxAffectation(auth.config().getTaxAffectation());
        doc.setSubtotal(amounts.subtotal());
        doc.setTax(amounts.tax());
        doc.setDiscount(amounts.discount());
        doc.setTotal(amounts.total());
        int line = 1;
        for (DocumentCalculator.Line l : amounts.lines()) {
            ElectronicDocumentItem item = new ElectronicDocumentItem();
            item.setLineNumber(line++);
            item.setProductId(l.productId());
            item.setSku(truncate(l.sku(), 60));
            item.setDescription(truncate(l.description(), 250));
            item.setQuantity(l.quantity());
            item.setUnitPrice(l.unitPrice());
            item.setUnitValue(l.unitValue());
            item.setSubtotal(l.subtotal());
            item.setTax(l.tax());
            item.setTotal(l.total());
            doc.addItem(item);
        }
        queue(doc, actor);
        ElectronicDocument saved = documents.save(doc);
        event(saved, DocumentEventType.CREATED, "Comprobante registrado para el pedido #" + order.getId() + ".", actor);
        publisher.publishEvent(new DocumentCreatedEvent(saved.getId()));
        return saved;
    }

    /**
     * Nota de crédito por el total. El comprobante original pasa a CANCEL_PENDING y a CANCELLED
     * cuando la nota es aceptada; si la rechazan, vuelve a ACCEPTED.
     */
    @Transactional
    public DocumentDetail createCreditNote(Member member, Long documentId, CreditNoteRequest request) {
        ElectronicDocument original = documents.findByIdAndCompanyId(documentId, member.companyId())
                .orElseThrow(() -> new NotFoundException("Comprobante no encontrado"));
        if (!original.getType().isSale()) throw new BusinessException("Una nota de crédito no se anula con otra nota.");
        if (original.getStatus() != DocumentStatus.ACCEPTED || original.getCreditNoteId() != null) {
            throw new BusinessException(HttpStatus.CONFLICT, "DOCUMENT_NOT_CREDITABLE",
                    "Solo se emite una nota de crédito sobre un comprobante aceptado y sin anular.");
        }
        CreditNoteReason reason = parse(CreditNoteReason.class, request.reason(), "Motivo de nota de crédito inválido.");
        if (reason == CreditNoteReason.ERROR_RUC && original.getType() != DocumentType.FACTURA) {
            throw new BusinessException("El motivo 'error en el RUC' es solo para facturas.");
        }
        InvoicingConfigurationService.Authorization auth = configuration.authorize(member.companyId(), DocumentType.NOTA_CREDITO, original.getType());
        if (auth.config().getProvider().environment() != original.getEnvironment()) {
            throw new BusinessException(HttpStatus.CONFLICT, "ENVIRONMENT_MISMATCH",
                    "El comprobante es de otro modo de emisión (prueba o real) que el actual.");
        }
        DocumentNumberService.Reserved reserved = numbers.next(member.companyId(), original.getEnvironment(),
                DocumentType.NOTA_CREDITO, original.getSeries().charAt(0), null);

        ElectronicDocument note = new ElectronicDocument();
        note.setCompanyId(member.companyId());
        note.setOrderId(original.getOrderId());
        note.setCustomerId(original.getCustomerId());
        note.setType(DocumentType.NOTA_CREDITO);
        note.setEnvironment(original.getEnvironment());
        note.setProvider(original.getProvider());
        note.setSeries(reserved.series());
        note.setNumber(reserved.number());
        issuer(note, auth);
        note.setCustomerDocumentType(original.getCustomerDocumentType());
        note.setCustomerDocumentNumber(original.getCustomerDocumentNumber());
        note.setCustomerName(original.getCustomerName());
        note.setCustomerEmail(original.getCustomerEmail());
        note.setCustomerAddress(original.getCustomerAddress());
        note.setTaxAffectation(original.getTaxAffectation());
        note.setSubtotal(original.getSubtotal());
        note.setTax(original.getTax());
        note.setDiscount(original.getDiscount());
        note.setTotal(original.getTotal());
        note.setRelatedDocumentId(original.getId());
        note.setCreditReason(reason);
        note.setCreditDescription(truncate(blankToNull(request.description()), 250));
        for (ElectronicDocumentItem source : original.getItems()) {
            ElectronicDocumentItem item = new ElectronicDocumentItem();
            item.setLineNumber(source.getLineNumber());
            item.setProductId(source.getProductId());
            item.setSku(source.getSku());
            item.setDescription(source.getDescription());
            item.setQuantity(source.getQuantity());
            item.setUnitPrice(source.getUnitPrice());
            item.setUnitValue(source.getUnitValue());
            item.setSubtotal(source.getSubtotal());
            item.setTax(source.getTax());
            item.setTotal(source.getTotal());
            note.addItem(item);
        }
        queue(note, member.displayName());
        ElectronicDocument saved = documents.save(note);
        original.setStatus(DocumentStatus.CANCEL_PENDING);
        original.setCreditNoteId(saved.getId());
        documents.save(original);
        event(saved, DocumentEventType.CREATED, "Nota de crédito sobre " + original.fullNumber() + ": " + reason.label() + ".", member.displayName());
        event(original, DocumentEventType.CANCEL_PENDING, "Nota de crédito " + saved.fullNumber() + " en proceso.", member.displayName());
        audit.record(member, AuditAction.CREDIT_NOTE_CREATED, "ELECTRONIC_DOCUMENT", saved.getId(),
                Map.of("number", saved.fullNumber(), "related", original.fullNumber(), "reason", reason.name()));
        publisher.publishEvent(new DocumentCreatedEvent(saved.getId()));
        return detail(saved);
    }

    /** Reintento manual de un comprobante en ERROR (por ejemplo, después de corregir las credenciales). */
    @Transactional
    public DocumentDetail retry(Member member, Long documentId) {
        ElectronicDocument doc = find(member, documentId);
        if (doc.getStatus() != DocumentStatus.ERROR) {
            throw new BusinessException(HttpStatus.CONFLICT, "DOCUMENT_NOT_RETRYABLE", "Solo se reintenta un comprobante con error.");
        }
        doc.setAttempts(0);
        doc.setNextAttemptAt(LocalDateTime.now());
        doc.setStatus(DocumentStatus.PENDING);
        documents.save(doc);
        event(doc, DocumentEventType.MANUAL_RETRY, "Reintento pedido desde el panel.", member.displayName());
        audit.record(member, AuditAction.INVOICE_RETRIED, "ELECTRONIC_DOCUMENT", doc.getId(), Map.of("number", doc.fullNumber()));
        publisher.publishEvent(new DocumentCreatedEvent(doc.getId()));
        return detail(doc);
    }

    /** Reenvía el correo. Nunca vuelve a emitir: el número y el estado del comprobante no cambian. */
    @Transactional
    public DocumentDetail resendEmail(Member member, Long documentId, ResendEmailRequest request) {
        ElectronicDocument doc = find(member, documentId);
        if (doc.getStatus() != DocumentStatus.ACCEPTED && doc.getStatus() != DocumentStatus.CANCELLED
                && doc.getStatus() != DocumentStatus.CANCEL_PENDING) {
            throw new BusinessException(HttpStatus.CONFLICT, "DOCUMENT_NOT_ACCEPTED", "El comprobante todavía no fue aceptado.");
        }
        String email = request == null ? null : blankToNull(request.email());
        if (email != null) {
            email = email.toLowerCase(Locale.ROOT);
            if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") || email.length() > 150) {
                throw new BusinessException("El correo no es válido.");
            }
            doc.setCustomerEmail(email);
        }
        if (doc.getCustomerEmail() == null) throw new BusinessException("El comprobante no tiene correo: indicá uno.");
        doc.setEmailStatus(EmailStatus.PENDING);
        doc.setEmailAttempts(0);
        doc.setEmailNextAttemptAt(LocalDateTime.now());
        documents.save(doc);
        event(doc, DocumentEventType.EMAIL_RESENT, "Reenvío a " + maskEmail(doc.getCustomerEmail()) + ".", member.displayName());
        audit.record(member, AuditAction.INVOICE_EMAIL_RESENT, "ELECTRONIC_DOCUMENT", doc.getId(), Map.of("number", doc.fullNumber()));
        publisher.publishEvent(new EmailRequestedEvent(doc.getId()));
        return detail(doc);
    }

    /** Invalida el enlace público (y el QR impreso) y genera otro. */
    @Transactional
    public DocumentDetail revokePublicLink(Member member, Long documentId) {
        ElectronicDocument doc = find(member, documentId);
        doc.setPublicToken(Hashing.randomToken());
        documents.save(doc);
        event(doc, DocumentEventType.PUBLIC_LINK_REVOKED, "Se generó un enlace público nuevo; el anterior dejó de funcionar.", member.displayName());
        audit.record(member, AuditAction.INVOICE_PUBLIC_LINK_REVOKED, "ELECTRONIC_DOCUMENT", doc.getId(), Map.of("number", doc.fullNumber()));
        return detail(doc);
    }

    // ─── Consultas ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PageResponse<DocumentRow> search(Member member, String type, String status, String query, Long orderId,
                                            LocalDate from, LocalDate to, int page, int size) {
        Long companyId = member.companyId();
        ZoneId zone = clock.zone(companyId);
        DocumentType typeFilter = type == null || type.isBlank() ? null : parse(DocumentType.class, type, "Tipo inválido.");
        DocumentStatus statusFilter = status == null || status.isBlank() ? null : parse(DocumentStatus.class, status, "Estado inválido.");
        Specification<ElectronicDocument> spec = (root, cq, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("companyId"), companyId));
            if (typeFilter != null) p.add(cb.equal(root.get("type"), typeFilter));
            if (statusFilter != null) p.add(cb.equal(root.get("status"), statusFilter));
            if (orderId != null) p.add(cb.equal(root.get("orderId"), orderId));
            if (query != null && !query.isBlank()) {
                String q = query.strip().toUpperCase(Locale.ROOT);
                List<Predicate> any = new ArrayList<>();
                any.add(cb.like(cb.upper(root.get("customerName")), "%" + q + "%"));
                any.add(cb.like(root.get("customerDocumentNumber"), "%" + q + "%"));
                if (q.matches("^[BF][A-Z0-9]{3}-?\\d{1,8}$")) {
                    String series = q.substring(0, 4);
                    long number = Long.parseLong(q.substring(q.indexOf('-') >= 0 ? 5 : 4));
                    any.add(cb.and(cb.equal(root.get("series"), series), cb.equal(root.get("number"), number)));
                } else if (q.matches("^[BF][A-Z0-9]{3}$")) {
                    any.add(cb.equal(root.get("series"), q));
                }
                p.add(cb.or(any.toArray(Predicate[]::new)));
            }
            if (from != null) p.add(cb.greaterThanOrEqualTo(root.get("issuedAt"), clock.startOf(from, zone)));
            if (to != null) p.add(cb.lessThan(root.get("issuedAt"), clock.startOf(to.plusDays(1), zone)));
            return cb.and(p.toArray(Predicate[]::new));
        };
        Page<ElectronicDocument> result = documents.findAll(spec, PageRequest.of(Math.max(page, 0), PageResponse.clampSize(size),
                Sort.by(Sort.Order.desc("issuedAt"), Sort.Order.desc("id"))));
        return PageResponse.of(result, d -> new DocumentRow(d.getId(), d.getType().name(), d.getEnvironment() == Environment.TEST,
                d.getSeries(), d.getNumber(), d.fullNumber(), d.getCustomerName(), d.getCustomerDocumentType().name(),
                d.getCustomerDocumentNumber(), d.getTotal(), d.getStatus().name(), d.getEmailStatus().name(), d.getOrderId(),
                d.getRelatedDocumentId(), d.getCreditNoteId(), BusinessClock.withOffset(d.getIssuedAt())));
    }

    @Transactional(readOnly = true)
    public DocumentDetail get(Member member, Long documentId) {
        return detail(find(member, documentId));
    }

    /** Para descargar PDF/XML: siempre por empresa. */
    @Transactional(readOnly = true)
    public ElectronicDocument loadForFiles(Long companyId, Long documentId) {
        ElectronicDocument doc = documents.findByIdAndCompanyId(documentId, companyId)
                .orElseThrow(() -> new NotFoundException("Comprobante no encontrado"));
        doc.getItems().size();
        return doc;
    }

    @Transactional(readOnly = true)
    public ElectronicDocument loadRelated(ElectronicDocument doc) {
        if (doc.getRelatedDocumentId() == null) return null;
        return documents.findByIdAndCompanyId(doc.getRelatedDocumentId(), doc.getCompanyId()).orElse(null);
    }

    @Transactional(readOnly = true)
    public Optional<ElectronicDocument> loadPublic(String token) {
        if (token == null || !token.matches("^[A-Za-z0-9_-]{32,64}$")) return Optional.empty();
        return documents.findByPublicToken(token).map(d -> {
            d.getItems().size();
            return d;
        });
    }

    public PublicDocumentView publicView(ElectronicDocument d) {
        String customerDoc = d.getCustomerDocumentNumber() == null ? null
                : d.getCustomerDocumentType().label() + " " + maskDocument(d.getCustomerDocumentNumber(), d.getCustomerDocumentType());
        return new PublicDocumentView(d.getType().name(), d.getType().label(), d.getEnvironment() == Environment.TEST, d.fullNumber(),
                d.getStatus().name(), d.getIssuerRuc(), d.getIssuerTradeName() != null ? d.getIssuerTradeName() : d.getIssuerName(),
                d.getCustomerName(), customerDoc, DocumentRenderer.issueDate(d), d.getSubtotal(), d.getTax(), d.getTotal(),
                d.getItems().stream().map(i -> new PublicDocumentView.Item(i.getDescription(), i.getQuantity(), i.getTotal())).toList(),
                d.getStatus() == DocumentStatus.ACCEPTED || d.getStatus() == DocumentStatus.CANCELLED
                        || d.getStatus() == DocumentStatus.CANCEL_PENDING);
    }

    public String publicUrl(ElectronicDocument doc) {
        return doc.getPublicToken() == null ? null : frontendUrl.replaceAll("/+$", "") + "/comprobante/" + doc.getPublicToken();
    }

    // ─── Apoyo ───────────────────────────────────────────────────────────────

    private ElectronicDocument find(Member member, Long documentId) {
        return documents.findByIdAndCompanyId(documentId, member.companyId())
                .orElseThrow(() -> new NotFoundException("Comprobante no encontrado"));
    }

    private void queue(ElectronicDocument doc, String actor) {
        doc.setStatus(DocumentStatus.PENDING);
        doc.setAttempts(0);
        doc.setNextAttemptAt(LocalDateTime.now());
        doc.setIssuedAt(LocalDateTime.now());
        doc.setEmailStatus(EmailStatus.NOT_REQUESTED);
        doc.setPublicToken(Hashing.randomToken());
        doc.setCreatedBy(truncate(actor, 150));
    }

    private static void issuer(ElectronicDocument doc, InvoicingConfigurationService.Authorization auth) {
        doc.setIssuerRuc(auth.config().getTaxId());
        doc.setIssuerName(auth.config().getBusinessName() != null ? auth.config().getBusinessName() : auth.profile().getBusinessName());
        doc.setIssuerTradeName(auth.config().getTradeName());
        doc.setIssuerAddress(auth.config().getFiscalAddress());
    }

    private static void receiver(ElectronicDocument doc, Receiver receiver) {
        doc.setCustomerDocumentType(receiver.documentType());
        doc.setCustomerDocumentNumber(receiver.documentNumber());
        doc.setCustomerName(receiver.name());
        doc.setCustomerAddress(receiver.address());
        doc.setCustomerEmail(receiver.email());
    }

    void event(ElectronicDocument doc, DocumentEventType type, String message, String actor) {
        events.save(new DocumentEvent(doc.getId(), doc.getCompanyId(), type, message, actor));
    }

    DocumentDetail detail(ElectronicDocument d) {
        ElectronicDocument related = loadRelated(d);
        ElectronicDocument credit = d.getCreditNoteId() == null ? null
                : documents.findByIdAndCompanyId(d.getCreditNoteId(), d.getCompanyId()).orElse(null);
        List<String> actions = new ArrayList<>();
        boolean accepted = d.getStatus() == DocumentStatus.ACCEPTED;
        boolean hasFiles = accepted || d.getStatus() == DocumentStatus.CANCELLED || d.getStatus() == DocumentStatus.CANCEL_PENDING;
        if (hasFiles) {
            actions.add("PDF");
            actions.add("XML");
            actions.add("PRINT");
            actions.add("RESEND_EMAIL");
            actions.add("PUBLIC_LINK");
        }
        if (accepted && d.getType().isSale() && d.getCreditNoteId() == null) actions.add("CREDIT_NOTE");
        if (d.getStatus() == DocumentStatus.ERROR) actions.add("RETRY");
        return new DocumentDetail(d.getId(), d.getType().name(), d.getType().label(), d.getEnvironment() == Environment.TEST,
                d.getProvider().name(), d.getSeries(), d.getNumber(), d.fullNumber(), d.getStatus().name(), d.getProviderMessage(),
                d.getLastError(), d.getAttempts(), BusinessClock.withOffset(d.getNextAttemptAt()), d.getIssuerRuc(),
                d.getIssuerName(), d.getIssuerTradeName(), d.getIssuerAddress(), d.getCustomerDocumentType().name(),
                d.getCustomerDocumentNumber(), d.getCustomerName(), d.getCustomerEmail(), d.getCustomerAddress(),
                d.getTaxAffectation().name(), d.getSubtotal(), d.getTax(), d.getDiscount(), d.getTotal(), d.getCurrency(),
                AmountInWords.soles(d.getTotal()), DocumentRenderer.issueDate(d), BusinessClock.withOffset(d.getIssuedAt()),
                BusinessClock.withOffset(d.getAcceptedAt()), BusinessClock.withOffset(d.getRejectedAt()),
                d.getEmailStatus().name(), BusinessClock.withOffset(d.getEmailSentAt()), d.getOrderId(),
                related == null ? null : new DocumentDetail.Related(related.getId(), related.getType().name(), related.fullNumber(), related.getStatus().name()),
                credit == null ? null : new DocumentDetail.Related(credit.getId(), credit.getType().name(), credit.fullNumber(), credit.getStatus().name()),
                d.getCreditReason() == null ? null : d.getCreditReason().name(),
                d.getCreditReason() == null ? null : d.getCreditReason().label(), d.getCreditDescription(),
                d.getHashCode(), d.getQrText(), hasFiles ? publicUrl(d) : null,
                d.getItems().stream().map(i -> new DocumentDetail.Item(i.getLineNumber(), i.getProductId(), i.getSku(), i.getDescription(),
                        i.getQuantity(), i.getUnitPrice(), i.getUnitValue(), i.getSubtotal(), i.getTax(), i.getTotal())).toList(),
                events.findByDocumentIdAndCompanyIdOrderByCreatedAtAscIdAsc(d.getId(), d.getCompanyId()).stream()
                        .map(e -> new DocumentDetail.Event(e.getType().name(), e.getMessage(), e.getActor(), BusinessClock.withOffset(e.getCreatedAt())))
                        .toList(),
                actions);
    }

    private static DocumentType parseSaleType(String value) {
        DocumentType type = parse(DocumentType.class, value, "Tipo de comprobante inválido: BOLETA o FACTURA.");
        if (!type.isSale()) throw new BusinessException("La nota de crédito se emite desde el comprobante que corrige.");
        return type;
    }

    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) return "***" + email.substring(Math.max(at, 0));
        return email.charAt(0) + "***" + email.substring(at);
    }

    private static String maskDocument(String number, IdentityDocumentType type) {
        if (type == IdentityDocumentType.RUC) return number;
        if (number.length() <= 4) return "****";
        return "*".repeat(number.length() - 3) + number.substring(number.length() - 3);
    }

    @SafeVarargs
    private static <T> T first(T... values) {
        for (T value : values) {
            if (value instanceof String s ? !s.isBlank() : value != null) return value;
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() > max ? value.substring(0, max) : value;
    }

    static <E extends Enum<E>> E parse(Class<E> type, String value, String message) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(message);
        }
    }

    static String ruc(String ruc) {
        return TaxIdValidator.mask(ruc);
    }
}
