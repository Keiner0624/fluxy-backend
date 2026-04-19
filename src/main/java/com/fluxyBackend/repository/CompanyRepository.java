package com.fluxyBackend.repository;

import com.fluxyBackend.entity.Company;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CompanyRepository extends JpaRepository<Company,Long> {
    Optional<Company> findBySlug(String slug);
}
