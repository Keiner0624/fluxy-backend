package com.fluxyBackend.controller;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.repository.CompanyRepository;
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
        return companyService.createCompany(company);
    }

    @GetMapping
    public List<Company> list() {
        return companyService.getAllCompanies();
    }
}
