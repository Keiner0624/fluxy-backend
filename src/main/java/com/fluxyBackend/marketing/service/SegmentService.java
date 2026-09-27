package com.fluxyBackend.marketing.service;

import com.fluxyBackend.billing.EntitlementService;
import com.fluxyBackend.billing.Feature;
import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Customer;
import com.fluxyBackend.entity.OrderStatus;
import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.marketing.dto.SegmentView;
import com.fluxyBackend.marketing.enums.SegmentKey;
import com.fluxyBackend.marketing.repository.MarketingOrderQueries;
import com.fluxyBackend.marketing.repository.MarketingOrderQueries.BuyerStats;
import com.fluxyBackend.repository.CategoryRepository;
import com.fluxyBackend.repository.CustomerRepository;
import com.fluxyBackend.service.BusinessClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Segmentos de clientes a partir del historial de pedidos (sin cancelados). Quien pidió no
 * recibir promociones no entra en ningún segmento: tener su teléfono no alcanza para escribirle.
 */
@Service
@RequiredArgsConstructor
public class SegmentService {

    static final int NEW_DAYS = 30;
    static final int INACTIVE_DAYS = 30;
    static final int FREQUENT_ORDERS = 3;
    static final double HIGH_VALUE_SPENT = 300;
    private static final int MAX_CUSTOMERS = 500;

    private final MarketingOrderQueries orderQueries;
    private final CustomerRepository customers;
    private final CategoryRepository categories;
    private final EntitlementService entitlements;

    @Transactional(readOnly = true)
    public List<SegmentView> segments(Company company) {
        boolean advanced = entitlements.has(company, Feature.MARKETING_ADVANCED_SEGMENTS);
        List<Member> members = members(company.getId());
        List<SegmentView> result = new ArrayList<>();
        for (SegmentKey key : SegmentKey.values()) {
            boolean available = !key.advanced() || advanced;
            Long count = null;
            Long reachable = null;
            if (available && key != SegmentKey.CATEGORY_BUYERS) {
                List<Member> in = members.stream().filter(rule(key, LocalDateTime.now(), Set.of())).toList();
                count = (long) in.size();
                reachable = in.stream().filter(m -> m.customer().getPhoneKey() != null).count();
            }
            result.add(new SegmentView(key.name(), key.label(), key.rule(), key.advanced(), available, count, reachable));
        }
        return result;
    }

    /** Clientes de un segmento, del que compró más recientemente al más antiguo. */
    @Transactional(readOnly = true)
    public List<SegmentView.Customer> customers(Company company, SegmentKey key, Long categoryId) {
        if (key.advanced()) entitlements.require(company, Feature.MARKETING_ADVANCED_SEGMENTS);
        Set<Long> categoryBuyers = Set.of();
        if (key == SegmentKey.CATEGORY_BUYERS) {
            if (categoryId == null) throw new BusinessException("Elegí la categoría del segmento.");
            categories.findByIdAndCompany(categoryId, company)
                    .orElseThrow(() -> new BusinessException("La categoría no existe en tu tienda."));
            categoryBuyers = new HashSet<>(orderQueries.categoryBuyers(company.getId(), categoryId, OrderStatus.CANCELLED));
        }
        return members(company.getId()).stream()
                .filter(rule(key, LocalDateTime.now(), categoryBuyers))
                .sorted(Comparator.comparing((Member m) -> m.stats().getLastOrderAt(), Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(MAX_CUSTOMERS)
                .map(m -> new SegmentView.Customer(m.customer().getId(), m.customer().getName(), m.customer().getPhone(),
                        m.stats().getOrders(), spent(m.stats()), BusinessClock.withOffset(m.stats().getLastOrderAt())))
                .toList();
    }

    public static SegmentKey parse(String value) {
        try {
            return SegmentKey.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException("Segmento inválido.");
        }
    }

    /** Cuántos clientes que se pueden contactar llevan 30 días o más sin comprar. */
    @Transactional(readOnly = true)
    public long inactiveReachable(Long companyId) {
        LocalDateTime now = LocalDateTime.now();
        return members(companyId).stream()
                .filter(rule(SegmentKey.INACTIVE_30, now, Set.of()))
                .filter(m -> m.customer().getPhoneKey() != null)
                .count();
    }

    private List<Member> members(Long companyId) {
        Map<Long, Customer> byId = customers.findByCompanyId(companyId).stream()
                .filter(c -> !c.optedOutOfMarketing())
                .collect(Collectors.toMap(Customer::getId, Function.identity()));
        return orderQueries.buyerStats(companyId, OrderStatus.CANCELLED).stream()
                .filter(s -> byId.containsKey(s.getCustomerId()))
                .map(s -> new Member(byId.get(s.getCustomerId()), s))
                .toList();
    }

    private static Predicate<Member> rule(SegmentKey key, LocalDateTime now, Set<Long> categoryBuyers) {
        return m -> {
            BuyerStats s = m.stats();
            return switch (key) {
                case ALL -> s.getOrders() > 0;
                case NEW -> s.getFirstOrderAt() != null && !s.getFirstOrderAt().isBefore(now.minusDays(NEW_DAYS));
                case INACTIVE_30 -> s.getLastOrderAt() != null && !s.getLastOrderAt().isAfter(now.minusDays(INACTIVE_DAYS));
                case FREQUENT -> s.getOrders() >= FREQUENT_ORDERS;
                case HIGH_VALUE -> spent(s) >= HIGH_VALUE_SPENT;
                case CATEGORY_BUYERS -> categoryBuyers.contains(m.customer().getId());
            };
        };
    }

    private static double spent(BuyerStats stats) {
        return stats.getSpent() == null ? 0 : Math.round(stats.getSpent() * 100) / 100.0;
    }

    private record Member(Customer customer, BuyerStats stats) {}
}
