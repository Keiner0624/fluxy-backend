package com.fluxyBackend.marketing.service;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.OrderStatus;
import com.fluxyBackend.entity.Prodcut;
import com.fluxyBackend.marketing.dto.Opportunity;
import com.fluxyBackend.marketing.entity.MarketingCampaign;
import com.fluxyBackend.marketing.enums.CampaignEventType;
import com.fluxyBackend.marketing.enums.CampaignObjective;
import com.fluxyBackend.marketing.enums.CampaignStatus;
import com.fluxyBackend.marketing.enums.CampaignType;
import com.fluxyBackend.marketing.enums.SegmentKey;
import com.fluxyBackend.marketing.repository.CampaignEventRepository;
import com.fluxyBackend.marketing.repository.MarketingCampaignRepository;
import com.fluxyBackend.marketing.repository.MarketingOrderQueries;
import com.fluxyBackend.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Oportunidades simples, sin IA: situaciones que se leen de los pedidos y de las campañas.
 * Pocas y con el motivo a la vista; no se sugiere lo que ya tiene una campaña en curso.
 */
@Service
@RequiredArgsConstructor
public class OpportunityService {

    static final int MAX = 4;
    static final int MIN_INACTIVE = 3;
    static final long MIN_INTEREST_SESSIONS = 10;
    static final int STALE_PRODUCT_DAYS = 14;
    static final int MIN_ORDERS_FOR_TREND = 3;
    static final double DROP_RATIO = 0.7;
    static final double GROWTH_RATIO = 0.5;

    private static final List<String> PRIORITY = List.of("HIGH", "MEDIUM", "LOW");

    private final SegmentService segments;
    private final MarketingOrderQueries orderQueries;
    private final CampaignEventRepository events;
    private final MarketingCampaignRepository campaigns;
    private final ProductRepository products;

    @Transactional(readOnly = true)
    public List<Opportunity> detect(Company company) {
        Long companyId = company.getId();
        LocalDateTime now = LocalDateTime.now();
        List<MarketingCampaign> live = campaigns.findByCompanyIdAndStatusIn(companyId, CampaignStatus.LIVE);
        Set<Long> promotedProducts = live.stream().filter(c -> c.getType() == CampaignType.PRODUCT)
                .map(MarketingCampaign::getTargetId).collect(Collectors.toSet());
        Set<Long> promotedCategories = live.stream().filter(c -> c.getType() == CampaignType.CATEGORY)
                .map(MarketingCampaign::getTargetId).collect(Collectors.toSet());
        boolean winBackRunning = live.stream().anyMatch(c -> c.getObjective() == CampaignObjective.WIN_BACK);

        List<Opportunity> found = new ArrayList<>();

        // Ventas de la semana contra la anterior.
        MarketingOrderQueries.SalesWindow last = orderQueries.sales(companyId, now.minusDays(7), now, OrderStatus.CANCELLED);
        MarketingOrderQueries.SalesWindow previous = orderQueries.sales(companyId, now.minusDays(14), now.minusDays(7), OrderStatus.CANCELLED);
        double lastTotal = value(last == null ? null : last.getTotal());
        double previousTotal = value(previous == null ? null : previous.getTotal());
        if (previous != null && previous.getOrders() >= MIN_ORDERS_FOR_TREND && lastTotal < previousTotal * DROP_RATIO) {
            long drop = Math.round((1 - lastTotal / previousTotal) * 100);
            found.add(new Opportunity("SALES_DROP", "HIGH", "Tus ventas bajaron " + drop + "% esta semana",
                    money(lastTotal) + " en los últimos 7 días contra " + money(previousTotal) + " la semana anterior.",
                    "Impulsar con una campaña",
                    new Opportunity.Suggestion(CampaignType.STORE.name(), CampaignObjective.PROMOTION.name(), null, null, "Promo de la semana")));
        }

        // Clientes que dejaron de comprar.
        if (!winBackRunning) {
            long inactive = segments.inactiveReachable(companyId);
            if (inactive >= MIN_INACTIVE) {
                found.add(new Opportunity("INACTIVE_CUSTOMERS", inactive >= 10 ? "HIGH" : "MEDIUM",
                        inactive + " clientes no compran hace más de 30 días",
                        "Ya te compraron y tienen teléfono. Un mensaje con una promoción suele traer de vuelta a algunos.",
                        "Crear campaña de reactivación",
                        new Opportunity.Suggestion(CampaignType.STORE.name(), CampaignObjective.WIN_BACK.name(), null,
                                SegmentKey.INACTIVE_30.name(), "Te extrañamos")));
            }
        }

        // Productos: interés sin ventas y productos quietos.
        Map<Long, Long> unitsSold = orderQueries.productUnits(companyId, now.minusDays(30), OrderStatus.CANCELLED).stream()
                .collect(Collectors.toMap(MarketingOrderQueries.ProductUnits::getProductId,
                        u -> u.getUnits() == null ? 0L : u.getUnits(), Long::sum));
        Map<Long, Prodcut> catalog = products.findByCompany(company).stream()
                .filter(p -> p.getStatus() != Prodcut.Status.HIDDEN)
                .collect(Collectors.toMap(Prodcut::getId, p -> p));

        Long interestProduct = null;
        events.productInterest(companyId, CampaignEventType.PRODUCT_VIEW, now.minusDays(30)).stream()
                .filter(i -> i.getSessions() >= MIN_INTEREST_SESSIONS && catalog.containsKey(i.getProductId()))
                .filter(i -> unitsSold.getOrDefault(i.getProductId(), 0L) <= 1 && !promotedProducts.contains(i.getProductId()))
                .max(Comparator.comparingLong(CampaignEventRepository.ProductInterest::getSessions))
                .ifPresent(i -> {
                    Prodcut p = catalog.get(i.getProductId());
                    long sold = unitsSold.getOrDefault(p.getId(), 0L);
                    found.add(new Opportunity("PRODUCT_INTEREST", "MEDIUM", p.getName() + " tiene visitas pero casi no se vende",
                            i.getSessions() + " personas abrieron su ficha desde tus campañas en 30 días y se "
                                    + (sold == 1 ? "vendió 1 unidad" : "vendieron " + sold + " unidades")
                                    + ". Revisá el precio o la ficha, o probá con una promoción.",
                            "Crear promoción",
                            new Opportunity.Suggestion(CampaignType.PRODUCT.name(), CampaignObjective.PROMOTION.name(), p.getId(), null,
                                    "Promo " + p.getName())));
                });
        for (Opportunity o : found) {
            if ("PRODUCT_INTEREST".equals(o.key())) interestProduct = o.suggestion().targetId();
        }
        Long skip = interestProduct;
        catalog.values().stream()
                .filter(p -> p.getStock() > 0 && !Objects.equals(p.getId(), skip) && !promotedProducts.contains(p.getId()))
                .filter(p -> p.getCreatedAt() == null || p.getCreatedAt().isBefore(now.minusDays(STALE_PRODUCT_DAYS)))
                .filter(p -> unitsSold.getOrDefault(p.getId(), 0L) == 0)
                .max(Comparator.comparingInt(Prodcut::getStock))
                .ifPresent(p -> found.add(new Opportunity("SLOW_PRODUCT", p.getStock() >= 10 ? "MEDIUM" : "LOW",
                        p.getName() + " no se vendió en 30 días",
                        "Tenés " + p.getStock() + (p.getStock() == 1 ? " unidad" : " unidades")
                                + " en stock y ninguna venta en el último mes.",
                        "Crear campaña del producto",
                        new Opportunity.Suggestion(CampaignType.PRODUCT.name(), CampaignObjective.SELL_PRODUCT.name(), p.getId(), null,
                                p.getName()))));

        // La categoría que más crece.
        Map<Long, MarketingOrderQueries.CategorySales> before = orderQueries
                .categorySales(companyId, now.minusDays(28), now.minusDays(14), OrderStatus.CANCELLED).stream()
                .collect(Collectors.toMap(MarketingOrderQueries.CategorySales::getCategoryId, c -> c));
        orderQueries.categorySales(companyId, now.minusDays(14), now, OrderStatus.CANCELLED).stream()
                .filter(c -> c.getOrders() >= MIN_ORDERS_FOR_TREND && !promotedCategories.contains(c.getCategoryId()))
                .map(c -> Map.entry(c, growth(c, before.get(c.getCategoryId()))))
                .filter(e -> e.getValue() >= GROWTH_RATIO)
                .max(Map.Entry.comparingByValue())
                .ifPresent(e -> {
                    MarketingOrderQueries.CategorySales c = e.getKey();
                    String change = Double.isInfinite(e.getValue()) ? "cuando antes no tenía ventas"
                            : Math.round(e.getValue() * 100) + "% más en importe que las dos semanas anteriores";
                    found.add(new Opportunity("GROWING_CATEGORY", "LOW", c.getName() + " está creciendo",
                            c.getOrders() + " pedidos en 14 días, " + change + ".",
                            "Destacar la categoría",
                            new Opportunity.Suggestion(CampaignType.CATEGORY.name(), CampaignObjective.VISITS.name(),
                                    c.getCategoryId(), null, c.getName())));
                });

        return found.stream()
                .sorted(Comparator.comparingInt(o -> PRIORITY.indexOf(o.priority())))
                .limit(MAX)
                .toList();
    }

    private static double growth(MarketingOrderQueries.CategorySales now, MarketingOrderQueries.CategorySales before) {
        double current = value(now.getRevenue());
        double previous = before == null ? 0 : value(before.getRevenue());
        if (previous <= 0) return current > 0 ? Double.POSITIVE_INFINITY : 0;
        return (current - previous) / previous;
    }

    private static double value(Double amount) {
        return amount == null ? 0 : amount;
    }

    private static String money(double amount) {
        return String.format(Locale.ROOT, "S/ %.2f", amount);
    }
}
