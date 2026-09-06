package com.fluxyBackend.service;

import com.fluxyBackend.exception.NotFoundException;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.text.Normalizer;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class CompanyService {
    private final CompanyRepository companyRespository;

    public Company createCompany(Company company) {
        String requestedSlug = company.getSlug();
        String baseSlug = (requestedSlug == null || requestedSlug.isBlank())
                ? generateSlug(company.getName())
                : generateSlug(requestedSlug);
        company.setSlug(generateUniqueSlug(baseSlug));
        return companyRespository.save(company);
    }

    public List<Company> getAllCompanies() {
        return companyRespository.findAll();
    }

    public Company getById(Long id) {
        return companyRespository.findById(id)
                .orElseThrow(() -> new NotFoundException("Empresa no encontrada"));
    }

    private String generateUniqueSlug(String baseSlug) {
        String candidate = baseSlug;
        int suffix = 2;
        while (companyRespository.existsBySlug(candidate)) {
            candidate = baseSlug + "-" + suffix;
            suffix++;
        }
        return candidate;
    }

    private String generateSlug(String name) {
        if (name == null || name.isBlank()) {
            return "store";
        }
        String slug = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.isBlank() ? "store" : slug;
    }
    public Company updateConfig(Long companyId, Company updates) {
        Company company = companyRespository.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Empresa no encontrada"));

        if (updates.getName() != null && !updates.getName().isBlank()) {
            company.setName(updates.getName());
        }
        if (updates.getDescription() != null) {
            company.setDescription(updates.getDescription());
        }
        if (updates.getPhone() != null) {
            company.setPhone(updates.getPhone());
        }
        if (updates.getAddress() != null) {
            company.setAddress(updates.getAddress());
        }
        if (updates.getEmail() != null) {
            company.setEmail(updates.getEmail());
        }
        if (updates.getLogoUrl() != null) {
            company.setLogoUrl(updates.getLogoUrl());
        }
        if (updates.getStoreStyle() != null) {
            company.setStoreStyle(updates.getStoreStyle());
        }

        return companyRespository.save(company);
    }

}
