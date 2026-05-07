package com.fluxyBackend.repository;

import com.fluxyBackend.entity.Coupon;
import com.fluxyBackend.entity.Company;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CouponRepository extends JpaRepository<Coupon, Long> {
    List<Coupon> findByCompany(Company company);
    Optional<Coupon> findByCodeIgnoreCaseAndCompany(String code, Company company);
}