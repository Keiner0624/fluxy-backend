package com.fluxyBackend.billing;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BillingPaymentRepository extends JpaRepository<BillingPayment, Long> {
    List<BillingPayment> findByCompanyIdOrderByPaidAtDescIdDesc(Long companyId, Pageable pageable);

    boolean existsByProviderPaymentId(String providerPaymentId);
}
