package com.fluxyBackend.service;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

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
                .orElseThrow(() -> new RuntimeException("Empresa no encontrada"));
    }

    private String generateUniqueSlug(String baseSlug) {
        String candidate = baseSlug;
        int suffix = 2;
        while (companyRespository.findBySlug(candidate).isPresent()) {
            candidate = baseSlug + "-" + suffix;
            suffix++;
        }
        return candidate;
    }

    private String generateSlug(String name) {
        return name.toLowerCase()
                .replaceAll("[áàäâ]", "a")
                .replaceAll("[éèëê]", "e")
                .replaceAll("[íìïî]", "i")
                .replaceAll("[óòöô]", "o")
                .replaceAll("[úùüû]", "u")
                .replaceAll("[^a-z0-9\\s]", "")
                .replaceAll("\\s+", "-")
                .trim();
    }
}
