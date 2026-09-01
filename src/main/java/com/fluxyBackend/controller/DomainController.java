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
import java.net.IDN;
import java.util.Locale;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/domains")
@RequiredArgsConstructor
public class DomainController {

    private static final Pattern DOMAIN_PATTERN = Pattern.compile(
            "^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$");

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

        try {
            domain = normalizeDomain(domain);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }

        if (company.getCustomDomain() != null
                && !company.getCustomDomain().equalsIgnoreCase(domain)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "message", "Elimina el dominio actual antes de registrar uno nuevo."));
        }
        if (companyRepository.findByCustomDomain(domain)
                .filter(existing -> !existing.getId().equals(company.getId()))
                .isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "message", "Ese dominio ya está asociado a otra tienda."));
        }

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

        if (!vercelDomainService.removeDomain(domain)) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "message", "No se pudo eliminar el dominio en Vercel."));
        }
        company.setCustomDomain(null);
        companyRepository.save(company);

        return ResponseEntity.ok(Map.of("message", "Dominio eliminado correctamente."));
    }

    private String normalizeDomain(String rawDomain) {
        String value = rawDomain.trim().toLowerCase(Locale.ROOT)
                .replaceFirst("^https?://", "")
                .replaceFirst("^www\\.", "")
                .replaceAll("[./]+$", "");
        if (value.contains("/") || value.contains("?") || value.contains("#") || value.contains(":")) {
            throw new IllegalArgumentException("El dominio no debe incluir ruta, puerto ni parámetros.");
        }
        String ascii;
        try {
            ascii = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("El dominio no es válido.");
        }
        if (!DOMAIN_PATTERN.matcher(ascii).matches()) {
            throw new IllegalArgumentException("El dominio no es válido.");
        }
        return ascii;
    }
}
