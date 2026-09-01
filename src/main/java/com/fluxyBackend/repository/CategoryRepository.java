package com.fluxyBackend.repository;

import com.fluxyBackend.entity.Category;
import com.fluxyBackend.entity.Company;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    List<Category> findByCompanyOrderByNameAsc(Company company);
    Optional<Category> findByIdAndCompany(Long id, Company company);
}
