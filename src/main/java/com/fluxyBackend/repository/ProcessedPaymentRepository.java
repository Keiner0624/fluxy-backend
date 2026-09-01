package com.fluxyBackend.repository;

import com.fluxyBackend.entity.ProcessedPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessedPaymentRepository extends JpaRepository<ProcessedPayment, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO processed_payments (payment_id, company_id, processed_at)
            VALUES (:paymentId, :companyId, CURRENT_TIMESTAMP)
            ON CONFLICT (payment_id) DO NOTHING
            """, nativeQuery = true)
    int markAsProcessed(@Param("paymentId") String paymentId,
                        @Param("companyId") Long companyId);
}
