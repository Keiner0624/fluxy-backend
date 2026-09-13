package com.fluxyBackend.repository;

import com.fluxyBackend.entity.OrderStatusChange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OrderStatusChangeRepository extends JpaRepository<OrderStatusChange, Long> {
    List<OrderStatusChange> findByOrderIdOrderByChangedAtAscIdAsc(Long orderId);

    @Modifying
    @Query("DELETE FROM OrderStatusChange c WHERE c.companyId = :companyId")
    void deleteByCompanyId(@Param("companyId") Long companyId);
}
