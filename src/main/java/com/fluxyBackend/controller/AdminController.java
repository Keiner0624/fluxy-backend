package com.fluxyBackend.controller;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Company.Plan;
import com.fluxyBackend.entity.Role;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.repository.CompanyRepository;
import com.fluxyBackend.repository.OrderRepository;
import com.fluxyBackend.repository.ProductRepository;
import com.fluxyBackend.repository.UserRepository;
import com.fluxyBackend.repository.PasswordResetTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final UserRepository               userRepository;
    private final CompanyRepository            companyRepository;
    private final OrderRepository              orderRepository;
    private final ProductRepository            productRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;

    private User requireAdmin(Authentication auth) {
        User user = userRepository.findByEmailIgnoreCase(auth.getName())
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));
        if (user.getRole() != Role.ADMIN) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Acceso denegado");
        }
        return user;
    }

    // ─── Métricas generales ───────────────────────────────────────────────────
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics(Authentication auth) {
        requireAdmin(auth);

        List<Company> all = companyRepository.findAll();
        long total    = all.size();
        long free     = all.stream().filter(c -> c.getPlan() == null || c.getPlan() == Plan.FREE).count();
        long pro      = all.stream().filter(c -> c.getPlan() == Plan.PRO).count();
        long business = all.stream().filter(c -> c.getPlan() == Plan.BUSINESS).count();
        double ingresos = (pro * 19.0) + (business * 39.0);
        long pedidos = orderRepository.count();

        // Vendedores nuevos hoy
        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        long hoy = all.stream()
                .filter(c -> c.getCreatedAt() != null && c.getCreatedAt().isAfter(startOfDay))
                .count();

        // Vendedores nuevos esta semana
        LocalDateTime startOfWeek = LocalDate.now().minusDays(7).atStartOfDay();
        long semana = all.stream()
                .filter(c -> c.getCreatedAt() != null && c.getCreatedAt().isAfter(startOfWeek))
                .count();

        return ResponseEntity.ok(Map.of(
                "totalVendedores",  total,
                "planFree",         free,
                "planPro",          pro,
                "planBusiness",     business,
                "ingresosTotales",  ingresos,
                "totalPedidos",     pedidos,
                "nuevosHoy",        hoy,
                "nuevosSemana",     semana
        ));
    }

    // ─── Vendedores por día (últimos 30 días) para gráfica ───────────────────
    @GetMapping("/metrics/vendors-per-day")
    public ResponseEntity<List<Map<String, Object>>> getVendorsPerDay(Authentication auth) {
        requireAdmin(auth);

        LocalDateTime desde = LocalDate.now().minusDays(29).atStartOfDay();
        List<Company> companies = companyRepository.findAll().stream()
                .filter(c -> c.getCreatedAt() != null && c.getCreatedAt().isAfter(desde))
                .toList();

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM");
        Map<String, Long> byDay = new LinkedHashMap<>();

        // Inicializar todos los días en 0
        for (int i = 29; i >= 0; i--) {
            String key = LocalDate.now().minusDays(i).format(fmt);
            byDay.put(key, 0L);
        }

        // Contar registros por día
        for (Company c : companies) {
            String key = c.getCreatedAt().toLocalDate().format(fmt);
            byDay.merge(key, 1L, Long::sum);
        }

        List<Map<String, Object>> result = byDay.entrySet().stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("date", e.getKey());
                    m.put("count", e.getValue());
                    return m;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    // ─── Ingresos por mes (últimos 6 meses) ──────────────────────────────────
    @GetMapping("/metrics/revenue-per-month")
    public ResponseEntity<List<Map<String, Object>>> getRevenuePerMonth(Authentication auth) {
        requireAdmin(auth);

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MMM");
        Map<String, Double> byMonth = new LinkedHashMap<>();

        for (int i = 5; i >= 0; i--) {
            String key = LocalDate.now().minusMonths(i).format(fmt);
            byMonth.put(key, 0.0);
        }

        List<Company> all = companyRepository.findAll();
        for (Company c : all) {
            if (c.getPlanActivatedAt() == null || c.getPlan() == Plan.FREE) continue;
            LocalDate activatedMonth = c.getPlanActivatedAt().toLocalDate().withDayOfMonth(1);
            LocalDate sixMonthsAgo  = LocalDate.now().minusMonths(5).withDayOfMonth(1);
            if (activatedMonth.isBefore(sixMonthsAgo)) continue;

            String key = c.getPlanActivatedAt().toLocalDate().format(fmt);
            double amount = c.getPlan() == Plan.PRO ? 19.0 : 39.0;
            byMonth.merge(key, amount, Double::sum);
        }

        List<Map<String, Object>> result = byMonth.entrySet().stream()
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("month", e.getKey());
                    m.put("revenue", e.getValue());
                    return m;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    // ─── Lista de vendedores ──────────────────────────────────────────────────
    @GetMapping("/vendors")
    public ResponseEntity<List<Map<String, Object>>> getVendors(Authentication auth) {
        requireAdmin(auth);

        List<Map<String, Object>> vendors = companyRepository.findAll().stream()
                .sorted(Comparator.comparing(
                        c -> c.getCreatedAt() != null ? c.getCreatedAt() : LocalDateTime.MIN,
                        Comparator.reverseOrder()
                ))
                .map(company -> {
                    User owner = userRepository.findByCompanyId(company.getId())
                            .stream().findFirst().orElse(null);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("companyId",     company.getId());
                    m.put("companyName",   company.getName()            != null ? company.getName()                    : "");
                    m.put("email",         owner                        != null ? owner.getEmail()                     : "");
                    m.put("plan",          company.getPlan()            != null ? company.getPlan().name()             : "FREE");
                    m.put("planExpiresAt", company.getPlanExpiresAt()   != null ? company.getPlanExpiresAt().toString(): "");
                    m.put("slug",          company.getSlug()            != null ? company.getSlug()                    : "");
                    m.put("createdAt",     company.getCreatedAt()       != null ? company.getCreatedAt().toString()    : "");
                    return m;
                })
                .collect(Collectors.toList());

        return ResponseEntity.ok(vendors);
    }

    // ─── Cambiar plan ─────────────────────────────────────────────────────────
    @PutMapping("/vendors/{companyId}/plan")
    public ResponseEntity<Map<String, String>> changePlan(
            @PathVariable Long companyId,
            @RequestBody Map<String, String> body,
            Authentication auth) {
        requireAdmin(auth);

        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new RuntimeException("Empresa no encontrada"));

        String planStr = body.getOrDefault("plan", "FREE").toUpperCase();
        int months = Integer.parseInt(body.getOrDefault("months", "1"));
        Plan plan = Plan.valueOf(planStr);

        company.setPlan(plan);
        company.setPlanActivatedAt(LocalDateTime.now());
        company.setPlanExpiresAt(plan == Plan.FREE ? null : LocalDateTime.now().plusMonths(months));
        companyRepository.save(company);

        return ResponseEntity.ok(Map.of("message", "Plan actualizado a " + planStr));
    }

    // ─── Eliminar vendedor ────────────────────────────────────────────────────
    @DeleteMapping("/vendors/{companyId}")
    @Transactional
    public ResponseEntity<Map<String, String>> deleteVendor(
            @PathVariable Long companyId,
            Authentication auth) {
        requireAdmin(auth);

        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new RuntimeException("Empresa no encontrada"));

        List<User> users = userRepository.findByCompanyId(companyId);
        for (User user : users) {
            passwordResetTokenRepository.deleteByUser_Email(user.getEmail());
        }
        orderRepository.deleteOrderItemsByCompanyId(companyId);
        orderRepository.deleteOrdersByCompanyId(companyId);
        productRepository.deleteByCompanyId(companyId);
        userRepository.deleteAll(users);
        companyRepository.delete(company);

        return ResponseEntity.ok(Map.of("message", "Vendedor eliminado correctamente."));
    }
}
