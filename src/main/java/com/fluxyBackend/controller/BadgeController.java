package com.fluxyBackend.controller;

import com.fluxyBackend.billing.Feature;
import com.fluxyBackend.billing.PlanCatalog;
import com.fluxyBackend.billing.SubscriptionService;
import com.fluxyBackend.entity.OrderPayment;
import com.fluxyBackend.entity.OrderStatus;
import com.fluxyBackend.entity.Prodcut;
import com.fluxyBackend.repository.AuditLogRepository;
import com.fluxyBackend.repository.CouponRepository;
import com.fluxyBackend.repository.CustomerRepository;
import com.fluxyBackend.repository.OrderPaymentRepository;
import com.fluxyBackend.repository.OrderRepository;
import com.fluxyBackend.repository.ProductRepository;
import com.fluxyBackend.repository.TeamInvitationRepository;
import com.fluxyBackend.security.access.AccessService;
import com.fluxyBackend.security.access.Member;
import com.fluxyBackend.security.access.Permission;
import com.fluxyBackend.service.AuditAction;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Contadores del menú del panel: lo que espera una acción en cada sección.
 * Cada número se calcula solo si quien pregunta puede ver esa sección.
 */
@Tag(name = "Panel", description = "Contadores del menú lateral.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequiredArgsConstructor
public class BadgeController {

    private static final List<String> SECURITY_ALERTS = List.of(AuditAction.LOGIN_FAILED, AuditAction.REFRESH_REUSE_DETECTED);

    private final AccessService accessService;
    private final OrderRepository orders;
    private final OrderPaymentRepository payments;
    private final CustomerRepository customers;
    private final ProductRepository products;
    private final CouponRepository coupons;
    private final TeamInvitationRepository invitations;
    private final AuditLogRepository auditLogs;
    private final SubscriptionService subscriptions;

    @Operation(summary = "Contadores del menú",
            description = "orders: pedidos por confirmar · payments: cobros por verificar · customers: clientes nuevos desde "
                    + "customersSince · inventory: productos con stock bajo o agotados · coupons: cupones activos que vencen en "
                    + "3 días · team: invitaciones pendientes · activity: alertas de seguridad desde activitySince · "
                    + "billing: 1 si el plan termina en 7 días o tiene la cancelación pedida. Los *Since son milisegundos epoch; "
                    + "sin ellos esos contadores no se calculan.")
    @GetMapping("/dashboard/badges")
    public Map<String, Object> badges(@RequestParam(required = false) Long customersSince,
                                      @RequestParam(required = false) Long activitySince) {
        Member member = accessService.current();
        Long companyId = member.companyId();
        Map<String, Object> badges = new LinkedHashMap<>();

        if (member.can(Permission.ORDER_VIEW)) {
            badges.put("orders", orders.countByCompanyIdAndStatus(companyId, OrderStatus.PENDING));
        }
        if (member.can(Permission.PAYMENT_VIEW)) {
            badges.put("payments", payments.countByCompanyIdAndStatus(companyId, OrderPayment.Status.PENDING));
        }
        if (member.can(Permission.CUSTOMER_VIEW) && customersSince != null) {
            badges.put("customers", customers.countByCompanyIdAndCreatedAtGreaterThanEqual(companyId, local(customersSince)));
        }
        if (member.can(Permission.INVENTORY_VIEW)) {
            ProductRepository.Stats stats = products.stats(companyId, Prodcut.Status.HIDDEN);
            badges.put("inventory", nz(stats.getLowStock()) + nz(stats.getOutOfStock()));
        }
        if (member.can(Permission.COUPON_VIEW) && PlanCatalog.has(member.company(), Feature.COUPONS)) {
            LocalDateTime now = LocalDateTime.now();
            badges.put("coupons", coupons.countExpiringBetween(companyId, now, now.plusDays(3)));
        }
        if (member.can(Permission.TEAM_VIEW)) {
            badges.put("team", invitations.countPending(companyId, Instant.now()));
        }
        if (member.can(Permission.AUDIT_VIEW) && activitySince != null) {
            badges.put("activity", auditLogs.countByCompanyIdAndActionInAndCreatedAtAfter(companyId, SECURITY_ALERTS, local(activitySince)));
        }
        if (member.can(Permission.BILLING_MANAGE)) {
            SubscriptionService.View view = subscriptions.view(member.company());
            boolean attention = !"FREE".equals(view.status()) && (view.cancelAtPeriodEnd() || view.daysLeft() <= 7)
                    && view.pendingChange() == null;
            badges.put("billing", attention ? 1 : 0);
            badges.put("billingDaysLeft", view.daysLeft());
        }
        return badges;
    }

    private static LocalDateTime local(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }

    private static long nz(Long value) {
        return value == null ? 0 : value;
    }
}
