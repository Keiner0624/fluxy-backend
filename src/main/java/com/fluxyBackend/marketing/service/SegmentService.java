package com.fluxyBackend.marketing.service;

import com.fluxyBackend.billing.EntitlementService;
import com.fluxyBackend.billing.Feature;
import com.fluxyBackend.customer.CustomerSource;
import com.fluxyBackend.customer.segmentation.CustomerSegmentType;
import com.fluxyBackend.customer.segmentation.CustomerSegmentationService;
import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Customer;
import com.fluxyBackend.entity.OrderStatus;
import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.marketing.dto.SegmentView;
import com.fluxyBackend.marketing.enums.SegmentKey;
import com.fluxyBackend.marketing.repository.MarketingOrderQueries;
import com.fluxyBackend.repository.CategoryRepository;
import com.fluxyBackend.repository.CustomerRepository;
import com.fluxyBackend.repository.ProductRepository;
import com.fluxyBackend.service.BusinessClock;
import com.fluxyBackend.service.CustomerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Predicate;

/**
 * Audiencias de Marketing sobre el CRM. Los segmentos de comportamiento los decide
 * CustomerSegmentationService (misma regla que en Clientes). Quien pidió no recibir promociones
 * no entra en ninguna audiencia: tener su teléfono no alcanza para escribirle.
 */
@Service
@RequiredArgsConstructor
public class SegmentService {

    private static final int MAX_CUSTOMERS = 500;

    private final CustomerSegmentationService segmentation;
    private final MarketingOrderQueries orderQueries;
    private final CustomerRepository customers;
    private final CategoryRepository categories;
    private final ProductRepository products;
    private final EntitlementService entitlements;

    @Transactional(readOnly = true)
    public List<SegmentView> segments(Company company) {
        boolean advanced = entitlements.has(company, Feature.MARKETING_ADVANCED_SEGMENTS);
        List<Member> members = members(company.getId());
        LocalDateTime now = LocalDateTime.now();
        List<SegmentView> result = new ArrayList<>();
        for (SegmentKey key : SegmentKey.values()) {
            boolean available = !key.advanced() || advanced;
            Long count = null;
            Long reachable = null;
            if (available && key.param() == SegmentKey.Param.NONE) {
                List<Member> in = members.stream().filter(rule(key, null, now)).toList();
                count = (long) in.size();
                reachable = in.stream().filter(m -> m.customer().getPhoneKey() != null).count();
            }
            result.add(new SegmentView(key.name(), key.label(), rule(key), key.advanced(), available, count, reachable,
                    key.param() == SegmentKey.Param.NONE ? null : key.param().name()));
        }
        return result;
    }

    /**
     * Clientes de una audiencia, del que compró más recientemente al más antiguo. categoryId para
     * CATEGORY_BUYERS; value es la etiqueta, el origen o el id de producto según la audiencia.
     */
    @Transactional(readOnly = true)
    public List<SegmentView.Customer> customers(Company company, SegmentKey key, Long categoryId, String value) {
        if (key.advanced()) entitlements.require(company, Feature.MARKETING_ADVANCED_SEGMENTS);
        Object param = switch (key.param()) {
            case CATEGORY, PRODUCT -> buyers(company, key, categoryId, value);
            case TAG, SOURCE -> value(key, value);
            case NONE -> null;
        };
        return members(company.getId()).stream()
                .filter(rule(key, param, LocalDateTime.now()))
                .sorted(Comparator.comparing((Member m) -> m.stats().lastOrderAt(), Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(MAX_CUSTOMERS)
                .map(m -> new SegmentView.Customer(m.customer().getId(), m.customer().getName(), m.customer().getPhone(),
                        m.stats().orders(), Math.round(m.stats().spent() * 100) / 100.0,
                        BusinessClock.withOffset(m.stats().lastOrderAt())))
                .toList();
    }

    /** Valida el dato elegido para la audiencia y lo devuelve normalizado (se guarda en la campaña). */
    public String validateValue(Company company, SegmentKey key, String value) {
        return switch (key.param()) {
            case TAG -> (String) value(key, value);
            case SOURCE -> ((CustomerSource) value(key, value)).name();
            case PRODUCT -> {
                Long productId = productId(value);
                products.findByIdAndCompany(productId, company)
                        .orElseThrow(() -> new BusinessException("El producto del segmento no existe en tu tienda."));
                yield productId.toString();
            }
            case CATEGORY, NONE -> null;
        };
    }

    public static SegmentKey parse(String value) {
        try {
            return SegmentKey.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException("Segmento inválido.");
        }
    }

    /** Cuántos clientes que se pueden contactar están inactivos. */
    @Transactional(readOnly = true)
    public long inactiveReachable(Long companyId) {
        LocalDateTime now = LocalDateTime.now();
        return members(companyId).stream()
                .filter(rule(SegmentKey.INACTIVE_30, null, now))
                .filter(m -> m.customer().getPhoneKey() != null)
                .count();
    }

    /** Texto de la regla; los segmentos automáticos usan los umbrales configurados. */
    public String rule(SegmentKey key) {
        if (key.type() != null) return segmentation.rule(key.type());
        return switch (key) {
            case TAG -> "Clientes con la etiqueta que elijas.";
            case SOURCE -> "Clientes que llegaron por el origen que elijas (tienda, punto de venta, campaña…).";
            case CATEGORY_BUYERS -> "Compraron productos de una categoría.";
            case PRODUCT_BUYERS -> "Compraron un producto en particular.";
            default -> "Clientes con al menos un pedido.";
        };
    }

    private Set<Long> buyers(Company company, SegmentKey key, Long categoryId, String value) {
        if (key == SegmentKey.CATEGORY_BUYERS) {
            if (categoryId == null) throw new BusinessException("Elegí la categoría del segmento.");
            categories.findByIdAndCompany(categoryId, company)
                    .orElseThrow(() -> new BusinessException("La categoría no existe en tu tienda."));
            return new HashSet<>(orderQueries.categoryBuyers(company.getId(), categoryId, OrderStatus.CANCELLED));
        }
        Long productId = Long.valueOf(validateValue(company, key, value));
        return new HashSet<>(orderQueries.productBuyers(company.getId(), productId, OrderStatus.CANCELLED));
    }

    private static Object value(SegmentKey key, String value) {
        if (key == SegmentKey.TAG) {
            List<String> tag = CustomerService.splitTags(value == null ? null : value.toLowerCase(Locale.ROOT));
            if (tag.isEmpty()) throw new BusinessException("Elegí la etiqueta del segmento.");
            return tag.get(0);
        }
        if (value == null || value.isBlank()) throw new BusinessException("Elegí el origen del segmento.");
        try {
            return CustomerSource.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Origen inválido.");
        }
    }

    private static Long productId(String value) {
        try {
            return Long.valueOf(value == null ? "" : value.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException("Elegí el producto del segmento.");
        }
    }

    /** Compradores de la empresa que aceptan promociones, con su historial (dos consultas, sin N+1). */
    private List<Member> members(Long companyId) {
        Map<Long, CustomerSegmentationService.Stats> stats = segmentation.statsByCustomer(companyId);
        return customers.findByCompanyId(companyId).stream()
                .filter(c -> !c.optedOutOfMarketing() && stats.containsKey(c.getId()))
                .map(c -> new Member(c, stats.get(c.getId())))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private Predicate<Member> rule(SegmentKey key, Object param, LocalDateTime now) {
        CustomerSegmentType type = key.type();
        return m -> switch (key.param()) {
            case NONE -> type == null ? m.stats().orders() > 0 : segmentation.classify(m.stats(), now).contains(type);
            case TAG -> CustomerService.splitTags(m.customer().getTags()).contains((String) param);
            case SOURCE -> m.customer().getSource() == param;
            case CATEGORY, PRODUCT -> ((Set<Long>) param).contains(m.customer().getId());
        };
    }

    private record Member(Customer customer, CustomerSegmentationService.Stats stats) {}
}
