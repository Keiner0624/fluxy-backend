package com.fluxyBackend.repository;

import com.fluxyBackend.entity.Company;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyRepository extends JpaRepository<Company,Long> {
}
