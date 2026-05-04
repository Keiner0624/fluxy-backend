package com.fluxyBackend.controller;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Role;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.repository.CompanyRepository;
import com.fluxyBackend.repository.OrderRepository;
import com.fluxyBackend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {
    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final OrderRepository orderRepository;

    //Verificar que el usuario sea admin
    private User requireAdmin(Authentication authentication){
        User user = userRepository.findByEmailIgnoreCase(authentication.getName())
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

        if (user.getRole() != Role.ADMIN)  {
            throw new RuntimeException("Acceso denegado");
        }

        return user;
    }

    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics(Authentication authentication){
        requireAdmin(authentication);
        List<Company> allCompanies = companyRepository.findAll();
        long totalVenededores = allCompanies.size();
        long planFree = allCompanies.stream().filter(c -> c.getPlan() == null || c.getPlan() == Company.Plan.FREE).count();
        long planPro = allCompanies.stream().filter(c -> c.getPlan() == Company.Plan.PRO).count();
        long planBusiness = allCompanies.stream().filter(c -> c.getPlan() == Company.Plan.BUSINESS).count();

        double ingresosTotales = (planPro * 19.0) + (planBusiness * 39.0);
        long totalPedidos = orderRepository.count();

        return ResponseEntity.ok(Map.of(
                "totalVenededores", totalVenededores,
                "planFree", planFree,
                "planPro", planPro,
                "planBusiness", planBusiness,
                "ingresosTotales", ingresosTotales,
                "totalPedidos", totalPedidos
        ));
    }

    @GetMapping("/vendors")
    public ResponseEntity<List<Map<String, Object>>> getVendors(Authentication authentication){
        requireAdmin(authentication);
        List<Map<String, Object>> vendors = companyRepository.findAll().stream()
                .map(company -> {
                    User owner = userRepository.findByCompanyId(company.getId())
                            .stream().findFirst().orElse(null);
                    return Map.<String, Object>of(
                            "companyId", company.getId(),
                            "companyName", company.getName() != null ? company.getName() : "",
                            "email", owner != null ? owner.getEmail() : "",
                            "plan", company.getPlan() != null ? company.getPlan().name() : "FREE",
                            "planExpiresAt", company.getPlanExpiresAt() != null ? company.getPlanExpiresAt().toString() : "",
                            "sluf", company.getSlug() != null ? company.getSlug() : "",
                            "createdAt", company.getPlanActivatedAt() != null ? company.getPlanActivatedAt().toString() : ""
                    );
                }).toList();

        return ResponseEntity.ok(vendors);
    }

    @PutMapping("/vendors/{companyId}/plan")
    public ResponseEntity<Map<String, String>> changePlan(
            @PathVariable Long companyId,
            @RequestBody Map<String, String> body,
            Authentication authentication) {
        requireAdmin(authentication);

        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new RuntimeException("Empresa no encontrada"));

        String planStr = body.getOrDefault("plan", "FREE").toUpperCase();
        int months = Integer.parseInt(body.getOrDefault("months", "1"));

        Company.Plan plan = Company.Plan.valueOf(planStr);
        company.setPlan(plan);
        company.setPlanActivatedAt(LocalDateTime.now());
        company.setPlanExpiresAt(plan == Company.Plan.FREE ? null : LocalDateTime.now().plusMonths(months));
        companyRepository.save(company);

        return ResponseEntity.ok(Map.of("message", "Plan actualizado a " + planStr));
    }

    @DeleteMapping("/vendors/{companyId}")
    public ResponseEntity<Map<String, String>> deleteVendor(@PathVariable Long companyId, Authentication authentication){
        requireAdmin(authentication);

        Company company = companyRepository.findById(companyId)
                        .orElseThrow(() -> new RuntimeException("Empresa no encontrada"));
        List<User> users = userRepository.findByCompanyId(companyId);
        userRepository.deleteAll(users);
        companyRepository.deleteById(companyId);
        return ResponseEntity.ok(Map.of("message", "Empresa eliminada"));
    }
}
