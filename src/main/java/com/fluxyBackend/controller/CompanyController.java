package com.fluxyBackend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

import com.fluxyBackend.exception.NotFoundException;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Company.Plan;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.repository.CompanyRepository;
import com.fluxyBackend.repository.UserRepository;
import com.fluxyBackend.service.CompanyService;
import com.fluxyBackend.service.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "Empresas", description = "Configuración de tiendas y prueba gratuita.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/companies")
@RequiredArgsConstructor
@Slf4j
public class CompanyController {

    private final CompanyService     companyService;
    private final UserRepository     userRepository;
    private final CompanyRepository  companyRepository;
    private final EmailService       emailService;

    // ─── Crear empresa ───────────────────────────────────────────────────────
    @Operation(summary = "Crear una empresa",
            description = "Requiere rol ADMIN.")
    @PostMapping
    public Company create(@RequestBody Company company) {
        return companyService.createCompany(company);
    }

    // ─── Listar empresas ─────────────────────────────────────────────────────
    @Operation(summary = "Listar todas las empresas",
            description = "Requiere rol ADMIN.")
    @GetMapping
    public List<Company> list() {
        return companyService.getAllCompanies();
    }

    // ─── Actualizar configuración ────────────────────────────────────────────
    @Operation(summary = "Actualizar la configuración de mi tienda",
            description = "Permite actualizar name, description, phone, address, email, logoUrl, storeStyle, primaryColor y paymentMethods.")
    @PutMapping("/config")
    public Company updateConfig(@RequestBody Company config, Authentication authentication) {
        User user = userRepository.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new NotFoundException("Usuario no encontrado"));
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
    @Operation(summary = "Consultar mi empresa",
            description = "Devuelve la empresa asociada al usuario autenticado.")
    @GetMapping("/my-company")
    public Company myCompany(Authentication authentication) {
        User user = userRepository.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new NotFoundException("Usuario no encontrado"));
        return user.getCompany();
    }

    // ─── Activar trial gratuito PRO por 1 mes ────────────────────────────────
    // POST /companies/trial
    @Operation(summary = "Activar la prueba gratuita de PRO",
            description = "Activa PRO por un mes una sola vez por empresa. Requiere plan FREE; responde 400 si ya existe un plan activo o se usó la prueba.")
    @PostMapping("/trial")
    public ResponseEntity<Map<String, Object>> activateTrial(Authentication authentication) {
        User user = userRepository.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new NotFoundException("Usuario no encontrado"));

        Company company = user.getCompany();

        // Verificar que no haya usado el trial antes
        if (company.isTrialUsed()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Ya utilizaste tu período de prueba gratuito."
            ));
        }

        // Verificar que esté en plan FREE
        if (company.getPlan() != Plan.FREE) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Ya tienes un plan activo."
            ));
        }

        // Activar PRO por 1 mes
        java.time.LocalDateTime expiresAt = java.time.LocalDateTime.now().plusMonths(1);
        company.setPlan(Plan.PRO);
        company.setPlanActivatedAt(java.time.LocalDateTime.now());
        company.setPlanExpiresAt(expiresAt);
        company.setTrialUsed(true);
        companyRepository.save(company);

        // Enviar email de confirmación
        try {
            emailService.sendTrialActivatedEmail(user.getEmail(), user.getFullName(), expiresAt);
        } catch (Exception e) {
            log.error("Error enviando email de trial a {}", user.getEmail(), e);
        }

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "¡Tu prueba gratuita de 1 mes está activa!",
                "plan", "PRO",
                "expiresAt", expiresAt.toString()
        ));
    }

}
