package com.fluxyBackend.repository;

import com.fluxyBackend.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
    List<Customer> findByCompanyId(Long companyId);
    Optional<Customer> findByIdAndCompanyId(Long id, Long companyId);
    Optional<Customer> findFirstByCompanyIdAndPhoneKey(Long companyId, String phoneKey);
    Optional<Customer> findFirstByCompanyIdAndPhoneKeyIsNullAndNameKey(Long companyId, String nameKey);
    long countByCompanyIdAndCreatedAtGreaterThanEqual(Long companyId, LocalDateTime since);

    @Modifying
    @Query("DELETE FROM Customer c WHERE c.companyId = :companyId")
    void deleteByCompanyId(@Param("companyId") Long companyId);
}
