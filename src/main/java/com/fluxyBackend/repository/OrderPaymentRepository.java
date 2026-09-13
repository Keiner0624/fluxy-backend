package com.fluxyBackend.repository;

import com.fluxyBackend.entity.OrderPayment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderPaymentRepository
        extends JpaRepository<OrderPayment, Long>, JpaSpecificationExecutor<OrderPayment> {

    List<OrderPayment> findByOrderIdOrderByCreatedAtAscIdAsc(Long orderId);
    List<OrderPayment> findByOrderIdIn(Collection<Long> orderIds);
    List<OrderPayment> findByCompanyId(Long companyId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM OrderPayment p WHERE p.id = :id AND p.companyId = :companyId")
    Optional<OrderPayment> findByIdAndCompanyIdForUpdate(@Param("id") Long id,
                                                         @Param("companyId") Long companyId);

    @Modifying
    @Query("DELETE FROM OrderPayment p WHERE p.companyId = :companyId")
    void deleteByCompanyId(@Param("companyId") Long companyId);
}
