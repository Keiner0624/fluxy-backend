package com.fluxyBackend.billing;

import com.fluxyBackend.billing.PaymentProvider.ProviderPayment;
import com.fluxyBackend.billing.SubscriptionService.ActivationResult;
import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.exception.NotFoundException;
import com.fluxyBackend.security.access.Member;
import com.fluxyBackend.service.BusinessClock;
import com.fluxyBackend.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/**
 * Aplica un pago de plan consultándolo en el proveedor. Lo usan el webhook y la vuelta del
 * comprador desde Mercado Pago: el que llegue primero activa el plan y el otro no suma nada
 * (applyPayment es idempotente). En ningún caso se confía en lo que dice el navegador: el pago
 * se lee de Mercado Pago.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentConfirmationService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d 'de' MMMM", Locale.forLanguageTag("es-PE"));

    /** status: APPLIED (se activó ahora), ALREADY_APPLIED, PENDING o REJECTED. */
    public record ReturnResult(String status, SubscriptionService.View subscription) {}

    private final SubscriptionService subscriptions;
    private final PaymentProvider provider;
    private final EmailService emailService;

    /** Webhook: aplica el pago si está aprobado y avisa por correo. */
    public Optional<ActivationResult> confirm(String paymentId) {
        Optional<ActivationResult> result = subscriptions.applyPayment(provider.fetchPayment(paymentId));
        result.ifPresent(this::notifyOwner);
        return result;
    }

    /**
     * Vuelta desde Mercado Pago (payment_id en la URL). Solo aplica pagos de la empresa de quien
     * vuelve: el de otra empresa responde 404, como si no existiera.
     */
    public ReturnResult confirmReturn(Member member, String paymentId) {
        if (paymentId == null || !paymentId.strip().matches("\\d{1,20}")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PAYMENT_ID_INVALID", "El identificador del pago no es válido.");
        }
        ProviderPayment payment = provider.fetchPayment(paymentId.strip());
        Long companyId;
        try {
            companyId = SubscriptionService.parseReference(payment.externalReference()).companyId();
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("Pago no encontrado");
        }
        if (!member.companyId().equals(companyId)) throw new NotFoundException("Pago no encontrado");

        String status;
        if (payment.approved()) {
            Optional<ActivationResult> result = subscriptions.applyPayment(payment);
            result.ifPresent(this::notifyOwner);
            status = result.isPresent() ? "APPLIED" : "ALREADY_APPLIED";
        } else if ("rejected".equalsIgnoreCase(payment.status()) || "cancelled".equalsIgnoreCase(payment.status())) {
            status = "REJECTED";
        } else {
            status = "PENDING";
        }
        log.info("Vuelta de Mercado Pago: pago {} de la empresa {} ({})", payment.id(), companyId, status);
        return new ReturnResult(status, subscriptions.view(member.company()));
    }

    private void notifyOwner(ActivationResult result) {
        log.info("Pago aplicado: plan {} ({}) para company {}", result.plan(), result.kind(), result.companyId());
        if (result.ownerEmail() == null) return;
        if (result.kind() == BillingPayment.Kind.DOWNGRADE) {
            String planName = PlanCatalog.info(result.plan()).name();
            emailService.sendBillingNotice(result.ownerEmail(), result.ownerName(), "Programaste el cambio a " + planName,
                    "Recibimos tu pago. Seguís con tu plan actual hasta el " + BusinessClock.withOffset(result.effectiveAt()).format(DAY)
                            + " y ese día empieza " + planName + ", pagado hasta el "
                            + BusinessClock.withOffset(result.paidUntil()).format(DAY) + ".");
        } else {
            emailService.sendPlanActivatedEmail(result.ownerEmail(), result.ownerName(), result.plan().name(), result.paidUntil());
        }
    }
}
