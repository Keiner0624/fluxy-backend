package com.fluxyBackend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;

import com.fluxyBackend.exception.NotFoundException;

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

@Tag(name = "Administración", description = "Operaciones reservadas al administrador de Fluxy; requieren rol ADMIN.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final UserRepository               userRepository;
    private final CompanyRepository            companyRepository;
    private final OrderRepository              orderRepository;
    private final ProductRepository            productRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final com.fluxyBackend.repository.OrderPaymentRepository orderPaymentRepository;
    private final com.fluxyBackend.repository.OrderStatusChangeRepository statusChangeRepository;
    private final com.fluxyBackend.repository.InventoryMovementRepository movementRepository;
    private final com.fluxyBackend.repository.TeamInvitationRepository invitationRepository;
    private final com.fluxyBackend.repository.MembershipRepository membershipRepository;
    private final com.fluxyBackend.repository.CompanyIntegrationsRepository integrationsRepository;
    private final com.fluxyBackend.repository.CustomerRepository customerRepository;
    private final jakarta.persistence.EntityManager entityManager;
    private final com.fluxyBackend.service.AuditService auditService;
    private final com.fluxyBackend.security.SessionService sessionService;

    private void requireAdmin(Authentication auth) {
        String email      = auth.getName();
        String adminEmail = System.getenv("ADMIN_EMAIL");

        // ✅ Aceptar si el email coincide con el admin configurado en env
        if (adminEmail != null && email.equalsIgnoreCase(adminEmail.trim())) return;

        // ✅ Aceptar si tiene rol ADMIN en la BD (fallback)
        boolean isAdminInDb = userRepository.findByEmailIgnoreCase(email)
                .map(u -> u.getRole() == Role.ADMIN)
                .orElse(false);

        if (!isAdminInDb) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Acceso denegado");
        }
    }

    // ─── Métricas generales ───────────────────────────────────────────────────
    @Operation(summary = "Consultar métricas generales",
            description = "Devuelve vendedores por plan, pedidos y altas recientes. ingresosTotales es una estimación basada en los planes actuales.")
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics(Authentication auth) {
        requireAdmin(auth);

        List<Company> all = companyRepository.findAll();
        long total    = all.size();
        long free     = all.stream().filter(c -> c.getPlan() == null || c.getPlan() == Plan.FREE).count();
        long pro      = all.stream().filter(c -> c.getPlan() == Plan.PRO).count();
        long business = all.stream().filter(c -> c.getPlan() == Plan.BUSINESS).count();
        double ingresos = (pro * 39.0) + (business * 59.0);
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
    @Operation(summary = "Consultar altas diarias de vendedores",
            description = "Devuelve date (dd/MM) y count de los últimos 30 días.")
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
    @Operation(summary = "Consultar ingresos estimados por mes",
            description = "Devuelve month y revenue de los últimos seis meses, estimados según el plan actual y su fecha de activación.")
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
            double amount = c.getPlan() == Plan.PRO ? 39.0 : 59.0;
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
    @Operation(summary = "Listar vendedores",
            description = "Devuelve las empresas con datos de su propietario y plan, ordenadas desde la más reciente.")
    @GetMapping("/vendors")
    public ResponseEntity<List<Map<String, Object>>> getVendors(Authentication auth) {
        requireAdmin(auth);

        List<Map<String, Object>> vendors = companyRepository.findAll().stream()
                .sorted(Comparator.comparing(
                        c -> c.getCreatedAt() != null ? c.getCreatedAt() : LocalDateTime.MIN,
                        Comparator.reverseOrder()
                ))
                .map(company -> {
                    User owner = userRepository.findFirstByCompanyIdAndRoleOrderByIdAsc(company.getId(), com.fluxyBackend.entity.Role.BUSINESS_OWNER).orElse(null);
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
    @Operation(summary = "Cambiar el plan de un vendedor",
            description = "Acepta FREE, PRO o BUSINESS. Por defecto usa FREE y un mes; PRO y BUSINESS admiten de 1 a 12 meses. FREE no tiene vencimiento.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = "{\"plan\":\"PRO\",\"months\":\"1\"}"))))
    @PutMapping("/vendors/{companyId}/plan")
    public ResponseEntity<Map<String, String>> changePlan(
            @PathVariable Long companyId,
            @RequestBody Map<String, String> body,
            Authentication auth) {
        requireAdmin(auth);

        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Empresa no encontrada"));

        String planStr = body.getOrDefault("plan", "FREE").toUpperCase();
        int months;
        Plan plan;
        try {
            months = Integer.parseInt(body.getOrDefault("months", "1"));
            plan = Plan.valueOf(planStr);
        } catch (IllegalArgumentException ex) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "Plan o duración inválidos");
        }
        if (plan != Plan.FREE && (months < 1 || months > 12)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "La cantidad de meses debe estar entre 1 y 12");
        }

        Plan previous = company.getPlan();
        company.setPlan(plan);
        company.setPlanActivatedAt(LocalDateTime.now());
        company.setPlanExpiresAt(plan == Plan.FREE ? null : LocalDateTime.now().plusMonths(months));
        companyRepository.save(company);
        auditService.record(companyId, null, com.fluxyBackend.service.AuditAction.PLAN_CHANGED, "COMPANY", companyId,
                Map.of("from", String.valueOf(previous), "to", plan.name(), "months", months, "by", "ADMIN"));

        return ResponseEntity.ok(Map.of("message", "Plan actualizado a " + planStr));
    }

    // ─── Eliminar vendedor ────────────────────────────────────────────────────
    @Operation(summary = "Eliminar un vendedor",
            description = "Elimina la empresa, sus usuarios, productos, pedidos y tokens de recuperación.")
    @DeleteMapping("/vendors/{companyId}")
    @Transactional
    public ResponseEntity<Map<String, String>> deleteVendor(
            @PathVariable Long companyId,
            Authentication auth) {
        requireAdmin(auth);

        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Empresa no encontrada"));

        List<User> users = userRepository.findByCompanyId(companyId);
        for (User user : users) {
            passwordResetTokenRepository.deleteByUser_Email(user.getEmail());
        }
        // Tablas sin clave foránea a la empresa: se borran explícitamente.
        orderPaymentRepository.deleteByCompanyId(companyId);
        statusChangeRepository.deleteByCompanyId(companyId);
        movementRepository.deleteByCompanyId(companyId);
        invitationRepository.deleteByCompanyId(companyId);
        membershipRepository.deleteByCompanyId(companyId);
        integrationsRepository.deleteById(companyId);

        orderRepository.deleteOrderItemsByCompanyId(companyId);
        orderRepository.deleteOrdersByCompanyId(companyId);
        customerRepository.deleteByCompanyId(companyId);
        productRepository.deleteByCompanyId(companyId);
        // Categorías, cupones y suscripciones push referencian a la empresa o a
        // sus usuarios: sin borrarlas antes, eliminar la empresa fallaba.
        entityManager.createQuery("DELETE FROM Category c WHERE c.company.id = :companyId")
                .setParameter("companyId", companyId).executeUpdate();
        entityManager.createQuery("DELETE FROM Coupon c WHERE c.company.id = :companyId")
                .setParameter("companyId", companyId).executeUpdate();
        entityManager.createQuery("DELETE FROM PushSubscription p WHERE p.user.id IN "
                        + "(SELECT u.id FROM User u WHERE u.company.id = :companyId)")
                .setParameter("companyId", companyId).executeUpdate();
        // Seguridad de las cuentas: sesiones, códigos, identidades y borradores de registro.
        List<Long> userIds = users.stream().map(User::getId).toList();
        if (!userIds.isEmpty()) {
            for (String entity : List.of("UserSession", "VerificationChallenge", "UserIdentity", "SignupDraft")) {
                entityManager.createQuery("DELETE FROM " + entity + " e WHERE e.userId IN :ids")
                        .setParameter("ids", userIds).executeUpdate();
            }
        }
        entityManager.createQuery("DELETE FROM OwnershipTransfer t WHERE t.companyId = :companyId")
                .setParameter("companyId", companyId).executeUpdate();
        userRepository.deleteAll(users);
        companyRepository.delete(company);
        auditService.recordSecurityEvent(companyId, null, "admin", com.fluxyBackend.service.AuditAction.COMPANY_ANONYMIZED,
                Map.of("by", "ADMIN_DELETE"));

        return ResponseEntity.ok(Map.of("message", "Vendedor eliminado correctamente."));
    }

    // ─── Cerrar sesiones de un negocio ────────────────────────────────────────
    @Operation(summary = "Cerrar todas las sesiones de un negocio",
            description = "Respuesta a incidentes: revoca los refresh tokens de todas las personas del negocio.")
    @PostMapping("/vendors/{companyId}/revoke-sessions")
    public Map<String, Object> revokeSessions(@PathVariable Long companyId, Authentication auth) {
        requireAdmin(auth);
        companyRepository.findById(companyId).orElseThrow(() -> new NotFoundException("Empresa no encontrada"));
        int revoked = sessionService.revokeAllForCompany(companyId, "ADMIN_INCIDENT");
        auditService.recordSecurityEvent(companyId, null, "admin", com.fluxyBackend.service.AuditAction.SESSIONS_REVOKED_ALL,
                Map.of("revoked", revoked, "by", "ADMIN"));
        return Map.of("revoked", revoked);
    }
}
