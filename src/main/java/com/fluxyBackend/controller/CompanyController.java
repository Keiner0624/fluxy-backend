package com.fluxyBackend.controller;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.service.CompanyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/companies")
@RequiredArgsConstructor
public class CompanyController {
    private final CompanyService companyService;
    @PostMapping
    public Company create(@RequestBody Company company) {
        if (company.getSlug() == null || company.getSlug().isEmpty()) {
            company.setSlug(generateSlug(company.getName()));
        }
        return companyService.createCompany(company);
    }

    private String generateSlug(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9\\s]", "").replaceAll("\\s+", "-")
                .trim();
    }

    @GetMapping
    public List<Company> list() {
        return companyService.getAllCompanies();
    }
}
