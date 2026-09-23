package com.fluxyBackend.repository;

import com.fluxyBackend.entity.ProcessedPayment;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedPaymentRepository extends JpaRepository<ProcessedPayment, Long> {

    /** Pagos aplicados antes de billing_payments: se consultan para no volver a aplicarlos. */
    boolean existsByPaymentId(String paymentId);
}
