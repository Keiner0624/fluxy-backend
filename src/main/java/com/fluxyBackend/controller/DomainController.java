package com.fluxyBackend.controller;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Company.Plan;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.repository.CompanyRepository;
import com.fluxyBackend.repository.UserRepository;
import com.fluxyBackend.service.VercelDomainService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/domains")
@RequiredArgsConstructor
public class DomainController {

    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final VercelDomainService vercelDomainService;

    // ─── Agregar dominio personalizado ───────────────────────────────────────
    // POST /domains/add
    // Body: { "domain": "mitienda.com" }
    @PostMapping("/add")
    public ResponseEntity<Map<String, Object>> addDomain(
            @RequestBody Map<String, String> body,
            Authentication authentication) {

        User user = userRepository.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

        Company company = user.getCompany();

        // Solo plan BUSINESS
        if (company.getPlan() == null || company.getPlan() != Plan.BUSINESS) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "message", "El dominio personalizado es exclusivo del plan Business.",
                    "upgradeRequired", true
            ));
        }

        String domain = body.get("domain");
        if (domain == null || domain.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "El dominio es requerido."));
        }

        // Limpiar dominio (quitar http/https/www)
        domain = domain.trim()
                .replaceAll("^https?://", "")
                .replaceAll("^www\\.", "")
                .replaceAll("/$", "")
                .toLowerCase();

        // Agregar a Vercel
        boolean success = vercelDomainService.addDomain(domain);
        if (!success) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "message", "Error al agregar el dominio a Vercel. Intenta de nuevo."
            ));
        }

        // Guardar en la empresa
        company.setCustomDomain(domain);
        companyRepository.save(company);

        return ResponseEntity.ok(Map.of(
                "message", "Dominio agregado correctamente.",
                "domain", domain,
                "status", "pending",
                "instructions", Map.of(
                        "type", "CNAME",
                        "name", "www",
                        "value", "cname.vercel-dns.com",
                        "note", "Para dominio raíz (sin www), apunta el registro A a 76.76.21.21"
                )
        ));
    }

    // ─── Verificar estado del dominio ────────────────────────────────────────
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getDomainStatus(Authentication authentication) {
        User user = userRepository.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

        Company company = user.getCompany();
        String domain = company.getCustomDomain();

        if (domain == null || domain.isBlank()) {
            return ResponseEntity.ok(Map.of("domain", "", "status", "none"));
        }

        String status = vercelDomainService.getDomainStatus(domain);
        return ResponseEntity.ok(Map.of("domain", domain, "status", status));
    }

    // ─── Eliminar dominio personalizado ──────────────────────────────────────
    @DeleteMapping("/remove")
    public ResponseEntity<Map<String, Object>> removeDomain(Authentication authentication) {
        User user = userRepository.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

        Company company = user.getCompany();
        String domain = company.getCustomDomain();

        if (domain == null || domain.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "No hay dominio configurado."));
        }

        vercelDomainService.removeDomain(domain);
        company.setCustomDomain(null);
        companyRepository.save(company);

        return ResponseEntity.ok(Map.of("message", "Dominio eliminado correctamente."));
    }
}