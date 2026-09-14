package com.fluxyBackend.controller;

import com.fluxyBackend.security.access.Permission;
import com.fluxyBackend.security.access.RequirePermission;

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
    private final com.fluxyBackend.security.access.AccessService accessService;
    private final com.fluxyBackend.service.AuditService auditService;

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
    @RequirePermission(Permission.SETTINGS_MANAGE)
    public Company updateConfig(@jakarta.validation.Valid @RequestBody CompanyConfigRequest config) {
        com.fluxyBackend.security.access.Member member = accessService.current();
        Company company = companyRepository.findById(member.companyId())
                .orElseThrow(() -> new NotFoundException("Empresa no encontrada"));

        // Solo estos campos: plan, slug, dominio y estado nunca se toman del cliente.
        if (config.name() != null && !config.name().isBlank())
            company.setName(config.name().strip());
        if (config.description() != null)
            company.setDescription(config.description());
        if (config.phone() != null)
            company.setPhone(config.phone().strip());
        if (config.address() != null)
            company.setAddress(config.address());
        if (config.email() != null)
            company.setEmail(config.email().strip());
        if (config.logoUrl() != null)
            company.setLogoUrl(config.logoUrl().isBlank() ? null : config.logoUrl().strip());
        if (config.storeStyle() != null)
            company.setStoreStyle(config.storeStyle());
        if (config.primaryColor() != null)
            company.setPrimaryColor(config.primaryColor());
        if (config.paymentMethods() != null)
            company.setPaymentMethods(config.paymentMethods());

        Company saved = companyRepository.save(company);
        auditService.record(member, com.fluxyBackend.service.AuditAction.SETTINGS_UPDATED, "COMPANY", company.getId(), null);
        return saved;
    }

    public record CompanyConfigRequest(
            @jakarta.validation.constraints.Size(max = 120) String name,
            @jakarta.validation.constraints.Size(max = 2000) String description,
            @jakarta.validation.constraints.Size(max = 30) String phone,
            @jakarta.validation.constraints.Size(max = 300) String address,
            @jakarta.validation.constraints.Size(max = 254) @jakarta.validation.constraints.Email String email,
            @jakarta.validation.constraints.Size(max = 2048)
            @jakarta.validation.constraints.Pattern(regexp = "^$|^https://\\S+$", message = "debe ser una URL https")
            String logoUrl,
            @jakarta.validation.constraints.Size(max = 20000) String storeStyle,
            @jakarta.validation.constraints.Pattern(regexp = "^$|^#[0-9A-Fa-f]{3,8}$", message = "debe ser un color hexadecimal")
            String primaryColor,
            @jakarta.validation.constraints.Size(max = 4000) String paymentMethods) {}

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
    @RequirePermission(Permission.BILLING_MANAGE)
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
        auditService.record(company.getId(), user, com.fluxyBackend.service.AuditAction.PLAN_CHANGED, "COMPANY",
                company.getId(), Map.of("from", "FREE", "to", "PRO", "by", "TRIAL"));

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
