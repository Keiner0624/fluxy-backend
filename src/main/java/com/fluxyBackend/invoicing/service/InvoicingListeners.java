package com.fluxyBackend.invoicing.service;

import com.fluxyBackend.entity.Order;
import com.fluxyBackend.entity.OrderStatus;
import com.fluxyBackend.invoicing.entity.InvoicingConfiguration;
import com.fluxyBackend.invoicing.enums.ConfigurationStatus;
import com.fluxyBackend.invoicing.enums.IssueTrigger;
import com.fluxyBackend.invoicing.event.DocumentAcceptedEvent;
import com.fluxyBackend.invoicing.event.DocumentCreatedEvent;
import com.fluxyBackend.invoicing.event.EmailRequestedEvent;
import com.fluxyBackend.invoicing.repository.InvoicingConfigurationRepository;
import com.fluxyBackend.repository.OrderPaymentRepository;
import com.fluxyBackend.repository.OrderRepository;
import com.fluxyBackend.service.AuditAction;
import com.fluxyBackend.service.AuditService;
import com.fluxyBackend.service.OrderPaymentService;
import com.fluxyBackend.service.OrderStatusChangedEvent;
import com.fluxyBackend.service.PaymentApprovedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Reacciones de facturación, siempre después de confirmada la transacción y en otro hilo:
 * el pago, el pedido o la respuesta al usuario nunca esperan al proveedor ni al correo.
 * Si algo falla acá, las tareas programadas lo retoman (la fila del comprobante es la cola).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InvoicingListeners {

    private final DocumentProcessor processor;
    private final DocumentEmailService emails;
    private final ElectronicDocumentService documents;
    private final InvoicingConfigurationRepository configurations;
    private final OrderRepository orders;
    private final OrderPaymentRepository payments;
    private final AuditService audit;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentCreated(DocumentCreatedEvent event) {
        try {
            processor.process(event.documentId());
        } catch (RuntimeException e) {
            log.warn("El comprobante {} se enviará en el próximo ciclo: {}", event.documentId(), e.getMessage());
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDocumentAccepted(DocumentAcceptedEvent event) {
        sendEmail(event.documentId());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEmailRequested(EmailRequestedEvent event) {
        sendEmail(event.documentId());
    }

    /** Emisión automática al quedar el pedido pagado por completo. */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentApproved(PaymentApprovedEvent event) {
        InvoicingConfiguration config = automatic(event.companyId(), IssueTrigger.PAYMENT_CONFIRMED);
        if (config == null) return;
        Order order = orders.findByIdAndCompanyId(event.orderId(), event.companyId()).orElse(null);
        if (order == null || order.getStatus() == OrderStatus.CANCELLED) return;
        String paymentStatus = OrderPaymentService.paymentStatus(order.getTotal() == null ? 0 : order.getTotal(),
                payments.findByOrderIdOrderByCreatedAtAscIdAsc(order.getId()));
        if (!"PAID".equals(paymentStatus)) return;
        issueAutomatically(event.companyId(), event.orderId());
    }

    /** Emisión automática al entregar el pedido. */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderStatusChanged(OrderStatusChangedEvent event) {
        if (event.status() != OrderStatus.DELIVERED) return;
        if (automatic(event.companyId(), IssueTrigger.ORDER_DELIVERED) == null) return;
        issueAutomatically(event.companyId(), event.orderId());
    }

    private InvoicingConfiguration automatic(Long companyId, IssueTrigger trigger) {
        InvoicingConfiguration config = configurations.findByCompanyId(companyId).orElse(null);
        if (config == null || config.getStatus() != ConfigurationStatus.ACTIVE || !config.isAutomaticIssuing()
                || config.getIssueTrigger() != trigger) return null;
        return config;
    }

    private void issueAutomatically(Long companyId, Long orderId) {
        try {
            documents.createAutomatic(companyId, orderId);
        } catch (RuntimeException e) {
            // Faltan datos (por ejemplo DNI en una boleta de S/ 700 o más): queda para emitir a mano.
            log.info("No se emitió automáticamente el comprobante del pedido {}: {}", orderId, e.getMessage());
            try {
                audit.record(companyId, null, AuditAction.INVOICE_FAILED, "ORDER", orderId,
                        Map.of("reason", String.valueOf(e.getMessage()), "by", "AUTOMATIC"));
            } catch (RuntimeException ignored) {
                // La auditoría no debe esconder el motivo original.
            }
        }
    }

    private void sendEmail(Long documentId) {
        try {
            emails.send(documentId);
        } catch (RuntimeException e) {
            log.warn("El correo del comprobante {} se reintentará: {}", documentId, e.getMessage());
        }
    }
}
