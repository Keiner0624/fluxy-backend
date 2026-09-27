package com.fluxyBackend.customer.segmentation;

import com.fluxyBackend.entity.OrderStatus;
import com.fluxyBackend.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Segmentos automáticos. Es la única fuente de verdad: el panel los muestra, Clientes filtra y
 * Marketing arma audiencias con esta misma regla. Los umbrales se configuran en
 * app.customers.segments.* (no hay números sueltos en el código).
 *
 * Cuenta los pedidos no cancelados: un pedido pendiente ya es una compra del cliente aunque el
 * negocio todavía no lo haya confirmado.
 */
@Service
public class CustomerSegmentationService {

    /** Historial de compras de un cliente. */
    public record Stats(long orders, double spent, LocalDateTime firstOrderAt, LocalDateTime lastOrderAt) {}

    public record Rules(int newDays, int recurringMinOrders, int recurringWindowDays, int frequentMinOrders,
                        int inactiveDays, double highValueSpent) {}

    private final OrderRepository orders;
    private final Rules rules;

    public CustomerSegmentationService(OrderRepository orders,
                                       @Value("${app.customers.segments.new_days:30}") int newDays,
                                       @Value("${app.customers.segments.recurring_min_orders:2}") int recurringMinOrders,
                                       @Value("${app.customers.segments.recurring_window_days:30}") int recurringWindowDays,
                                       @Value("${app.customers.segments.frequent_min_orders:3}") int frequentMinOrders,
                                       @Value("${app.customers.segments.inactive_days:30}") int inactiveDays,
                                       @Value("${app.customers.segments.high_value_spent:300}") double highValueSpent) {
        this.orders = orders;
        this.rules = new Rules(newDays, recurringMinOrders, recurringWindowDays, frequentMinOrders, inactiveDays, highValueSpent);
    }

    public Rules rules() {
        return rules;
    }

    /** Segmentos de un cliente a partir de sus compras. Sin compras no tiene segmentos. */
    public Set<CustomerSegmentType> classify(Stats stats, LocalDateTime now) {
        Set<CustomerSegmentType> segments = EnumSet.noneOf(CustomerSegmentType.class);
        if (stats == null || stats.orders() == 0 || stats.lastOrderAt() == null) return segments;
        boolean inactive = !stats.lastOrderAt().isAfter(now.minusDays(rules.inactiveDays()));
        if (stats.firstOrderAt() != null && !stats.firstOrderAt().isBefore(now.minusDays(rules.newDays()))) {
            segments.add(CustomerSegmentType.NEW);
        }
        if (stats.orders() >= rules.recurringMinOrders() && stats.lastOrderAt().isAfter(now.minusDays(rules.recurringWindowDays()))) {
            segments.add(CustomerSegmentType.RECURRING);
        }
        if (stats.orders() >= rules.frequentMinOrders()) segments.add(CustomerSegmentType.FREQUENT);
        if (inactive) segments.add(CustomerSegmentType.INACTIVE);
        if (stats.spent() >= rules.highValueSpent()) segments.add(CustomerSegmentType.HIGH_VALUE);
        return segments;
    }

    /** Historial de compras de todos los clientes de la empresa, en una sola consulta agregada. */
    public Map<Long, Stats> statsByCustomer(Long companyId) {
        return orders.purchaseStats(companyId, OrderStatus.CANCELLED).stream()
                .collect(Collectors.toMap(OrderRepository.PurchaseStats::getCustomerId, CustomerSegmentationService::toStats,
                        (a, b) -> a));
    }

    public Optional<Stats> statsOf(Long companyId, Long customerId) {
        return orders.purchaseStatsOf(companyId, customerId, OrderStatus.CANCELLED).stream().findFirst()
                .map(CustomerSegmentationService::toStats);
    }

    /** Texto humano de la regla, para mostrarla junto al segmento. */
    public String rule(CustomerSegmentType type) {
        return switch (type) {
            case NEW -> "Su primera compra fue en los últimos " + rules.newDays() + " días.";
            case RECURRING -> rules.recurringMinOrders() + " compras o más y compró en los últimos " + rules.recurringWindowDays() + " días.";
            case FREQUENT -> rules.frequentMinOrders() + " compras o más.";
            case INACTIVE -> "No compra desde hace " + rules.inactiveDays() + " días o más.";
            case HIGH_VALUE -> "Compró S/ " + Math.round(rules.highValueSpent()) + " o más en total.";
        };
    }

    private static Stats toStats(OrderRepository.PurchaseStats s) {
        return new Stats(s.getOrders(), s.getSpent() == null ? 0 : s.getSpent(), s.getFirstOrderAt(), s.getLastOrderAt());
    }
}
