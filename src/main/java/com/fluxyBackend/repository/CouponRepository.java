package com.fluxyBackend.repository;

import com.fluxyBackend.entity.Coupon;
import com.fluxyBackend.entity.Company;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface CouponRepository extends JpaRepository<Coupon, Long> {
    List<Coupon> findByCompany(Company company);
    Optional<Coupon> findByCodeIgnoreCaseAndCompany(String code, Company company);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Coupon c WHERE UPPER(c.code) = UPPER(:code) AND c.company = :company")
    Optional<Coupon> findByCodeIgnoreCaseAndCompanyForUpdate(@Param("code") String code,
                                                             @Param("company") Company company);
}
