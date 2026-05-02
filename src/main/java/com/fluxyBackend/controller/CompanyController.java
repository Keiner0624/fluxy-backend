package com.fluxyBackend.controller;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Company.Plan;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.repository.CompanyRepository;
import com.fluxyBackend.repository.UserRepository;
import com.fluxyBackend.service.CompanyService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/companies")
@RequiredArgsConstructor
public class CompanyController {

    private final CompanyService companyService;
    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;

    // ─── Crear empresa ───────────────────────────────────────────────────────
    @PostMapping
    public Company create(@RequestBody Company company) {
        if (company.getSlug() == null || company.getSlug().isEmpty()) {
            company.setSlug(generateSlug(company.getName()));
        }
        return companyService.createCompany(company);
    }

    private String generateSlug(String name) {
        return name.toLowerCase()
                .replaceAll("[^a-z0-9\\s]", "")
                .replaceAll("\\s+", "-")
                .trim();
    }

    // ─── Listar empresas ─────────────────────────────────────────────────────
    @GetMapping
    public List<Company> list() {
        return companyService.getAllCompanies();
    }

    // ─── Actualizar configuración ────────────────────────────────────────────
    @PutMapping("/config")
    public Company updateConfig(@RequestBody Company config, Authentication authentication) {
        User user = userRepository.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));
        Company company = user.getCompany();

        if (config.getName() != null && !config.getName().isBlank())
            company.setName(config.getName());
        if (config.getDescription() != null)
            company.setDescription(config.getDescription());
        if (config.getPhone() != null)
            company.setPhone(config.getPhone());
        if (config.getAddress() != null)
            company.setAddress(config.getAddress());
        if (config.getEmail() != null)
            company.setEmail(config.getEmail());
        if (config.getLogoUrl() != null)
            company.setLogoUrl(config.getLogoUrl());
        if (config.getStoreStyle() != null)
            company.setStoreStyle(config.getStoreStyle());
        if (config.getPrimaryColor() != null)
            company.setPrimaryColor(config.getPrimaryColor());
        if (config.getPaymentMethods() != null)
            company.setPaymentMethods(config.getPaymentMethods());

        return companyRepository.save(company);
    }

    // ─── Mi empresa ──────────────────────────────────────────────────────────
    @GetMapping("/my-company")
    public Company myCompany(Authentication authentication) {
        User user = userRepository.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));
        return user.getCompany();
    }

    // ─── Actualizar plan (activación manual por Yape/Plin) ───────────────────
    // PUT /companies/plan
    // Body: { "plan": "PRO", "months": 1 }
    @PutMapping("/plan")
    public Company updatePlan(@RequestBody Map<String, String> body,
                              Authentication authentication) {
        User user = userRepository.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

        Company company = user.getCompany();

        // Validar plan
        String planStr = body.get("plan");
        if (planStr == null || planStr.isBlank()) {
            throw new RuntimeException("El campo 'plan' es requerido.");
        }

        Plan plan;
        try {
            plan = Plan.valueOf(planStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new RuntimeException("Plan inválido: '" + planStr + "'. Valores válidos: FREE, PRO, BUSINESS.");
        }

        // Meses (por defecto 1)
        int months = 1;
        if (body.containsKey("months")) {
            try {
                months = Integer.parseInt(body.get("months"));
            } catch (NumberFormatException e) {
                throw new RuntimeException("El campo 'months' debe ser un número entero.");
            }
        }

        // Aplicar plan
        company.setPlan(plan);
        company.setPlanActivatedAt(LocalDateTime.now());
        company.setPlanExpiresAt(LocalDateTime.now().plusMonths(months));

        return companyRepository.save(company);
    }
}