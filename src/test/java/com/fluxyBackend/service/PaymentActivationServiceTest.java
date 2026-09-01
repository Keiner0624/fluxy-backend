package com.fluxyBackend.service;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.repository.CompanyRepository;
import com.fluxyBackend.repository.ProcessedPaymentRepository;
import com.fluxyBackend.repository.UserRepository;
import com.mercadopago.resources.payment.Payment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentActivationServiceTest {

    @Mock
    private CompanyRepository companyRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ProcessedPaymentRepository processedPaymentRepository;
    @Mock
    private Payment payment;

    private PaymentActivationService service;

    @BeforeEach
    void setUp() {
        service = new PaymentActivationService(
                companyRepository,
                userRepository,
                processedPaymentRepository,
                new PlanPricingService()
        );
    }

    @Test
    void rejectsPaymentWhoseAmountWasManipulated() {
        approvedPayment("1|PRO|1", new BigDecimal("0.01"));

        assertThrows(IllegalArgumentException.class, () -> service.activate(payment));
        verifyNoInteractions(companyRepository, processedPaymentRepository);
    }

    @Test
    void activatesOnceAndPreservesRemainingSubscriptionTime() {
        approvedPayment("1|PRO|1", new BigDecimal("39.00"));
        when(payment.getCurrencyId()).thenReturn("PEN");
        Company company = Company.builder()
                .id(1L)
                .plan(Company.Plan.PRO)
                .planExpiresAt(LocalDateTime.now().plusDays(10))
                .build();
        LocalDateTime previousExpiry = company.getPlanExpiresAt();

        when(companyRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(company));
        when(processedPaymentRepository.markAsProcessed("123", 1L)).thenReturn(1);
        when(userRepository.findFirstByCompanyId(1L)).thenReturn(Optional.empty());

        Optional<PaymentActivationService.ActivationResult> result = service.activate(payment);

        assertTrue(result.isPresent());
        assertEquals(Company.Plan.PRO, company.getPlan());
        assertEquals(previousExpiry.plusMonths(1), company.getPlanExpiresAt());
    }

    @Test
    void ignoresAlreadyProcessedPayment() {
        approvedPayment("1|BUSINESS|1", new BigDecimal("59.00"));
        when(payment.getCurrencyId()).thenReturn("PEN");
        Company company = Company.builder().id(1L).plan(Company.Plan.FREE).build();
        when(companyRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(company));
        when(processedPaymentRepository.markAsProcessed("123", 1L)).thenReturn(0);

        assertTrue(service.activate(payment).isEmpty());
        assertEquals(Company.Plan.FREE, company.getPlan());
    }

    private void approvedPayment(String reference, BigDecimal amount) {
        when(payment.getId()).thenReturn(123L);
        when(payment.getStatus()).thenReturn("approved");
        when(payment.getExternalReference()).thenReturn(reference);
        when(payment.getTransactionAmount()).thenReturn(amount);
    }
}
