package com.fluxyBackend.service;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Company.Plan;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.repository.CompanyRepository;
import com.fluxyBackend.repository.ProcessedPaymentRepository;
import com.fluxyBackend.repository.UserRepository;
import com.mercadopago.resources.payment.Payment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class PaymentActivationService {

    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final ProcessedPaymentRepository processedPaymentRepository;
    private final PlanPricingService pricingService;

    @Transactional
    public Optional<ActivationResult> activate(Payment payment) {
        if (payment == null || payment.getId() == null || !"approved".equals(payment.getStatus())) {
            return Optional.empty();
        }

        PaymentReference reference = parseReference(payment.getExternalReference());
        BigDecimal expectedAmount = pricingService.total(reference.plan(), reference.months());
        if (payment.getTransactionAmount() == null
                || expectedAmount.compareTo(payment.getTransactionAmount()) != 0
                || !PlanPricingService.CURRENCY.equalsIgnoreCase(payment.getCurrencyId())) {
            throw new IllegalArgumentException("El monto o la moneda del pago no coincide con el plan");
        }

        Company company = companyRepository.findByIdForUpdate(reference.companyId())
                .orElseThrow(() -> new IllegalArgumentException("Empresa no encontrada"));

        int inserted = processedPaymentRepository.markAsProcessed(
                String.valueOf(payment.getId()), company.getId());
        if (inserted == 0) {
            return Optional.empty();
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startsAt = company.getPlanExpiresAt() != null
                && company.getPlanExpiresAt().isAfter(now)
                ? company.getPlanExpiresAt()
                : now;
        LocalDateTime expiresAt = startsAt.plusMonths(reference.months());

        company.setPlan(reference.plan());
        company.setPlanActivatedAt(now);
        company.setPlanExpiresAt(expiresAt);

        User owner = userRepository.findFirstByCompanyId(company.getId()).orElse(null);
        return Optional.of(new ActivationResult(
                company.getId(),
                reference.plan(),
                expiresAt,
                owner == null ? null : owner.getEmail(),
                owner == null ? null : owner.getFullName()
        ));
    }

    private PaymentReference parseReference(String externalReference) {
        if (externalReference == null) {
            throw new IllegalArgumentException("Referencia de pago ausente");
        }
        String[] parts = externalReference.split("\\|", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Referencia de pago inválida");
        }
        try {
            Long companyId = Long.valueOf(parts[0]);
            Plan plan = pricingService.parsePlan(parts[1]);
            int months = pricingService.parseMonths(parts[2]);
            return new PaymentReference(companyId, plan, months);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Referencia de pago inválida", ex);
        }
    }

    private record PaymentReference(Long companyId, Plan plan, int months) {
    }

    public record ActivationResult(
            Long companyId,
            Plan plan,
            LocalDateTime expiresAt,
            String ownerEmail,
            String ownerName
    ) {
    }
}
