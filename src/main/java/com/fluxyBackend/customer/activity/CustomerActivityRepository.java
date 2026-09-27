package com.fluxyBackend.customer.activity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerActivityRepository extends JpaRepository<CustomerActivity, Long> {

    Page<CustomerActivity> findByCompanyIdAndCustomerId(Long companyId, Long customerId, Pageable pageable);

    boolean existsByCustomerIdAndTypeAndReferenceId(Long customerId, CustomerActivityType type, Long referenceId);

    @Modifying
    @Query("DELETE FROM CustomerActivity a WHERE a.companyId = :companyId")
    void deleteByCompanyId(@Param("companyId") Long companyId);
}
