package com.fluxyBackend.controller;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.repository.CompanyRepository;
import com.fluxyBackend.repository.UserRepository;
import com.fluxyBackend.service.CompanyService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/companies")
@RequiredArgsConstructor
public class CompanyController {
    private final CompanyService companyService;
    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;

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

    @PutMapping("/config")
    public Company updateConfig(@RequestBody Company config, Authentication authentication) {
        User user = userRepository.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));
        Company company = user.getCompany();

        if (config.getPhone() != null) company.setPhone(config.getPhone());
        if (config.getAddress() != null) company.setAddress(config.getAddress());
        if (config.getDescription() != null) company.setDescription(config.getDescription());
        if (config.getPrimaryColor() != null) company.setPrimaryColor(config.getPrimaryColor());
        return companyRepository.save(company);
    }

    @GetMapping("/my-company")
    public Company myCompany(Authentication authentication) {
        User user = userRepository.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));
        return user.getCompany();
    }
}
