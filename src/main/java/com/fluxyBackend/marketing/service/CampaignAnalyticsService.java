package com.fluxyBackend.marketing.service;

import com.fluxyBackend.entity.Coupon;
import com.fluxyBackend.entity.OrderStatus;
import com.fluxyBackend.marketing.dto.CampaignAnalytics;
import com.fluxyBackend.marketing.dto.CampaignMetrics;
import com.fluxyBackend.marketing.entity.MarketingCampaign;
import com.fluxyBackend.marketing.enums.CampaignChannel;
import com.fluxyBackend.marketing.enums.CampaignEventType;
import com.fluxyBackend.marketing.repository.CampaignEventRepository;
import com.fluxyBackend.marketing.repository.CampaignEventRepository.EventRow;
import com.fluxyBackend.marketing.repository.MarketingOrderQueries;
import com.fluxyBackend.repository.CouponRepository;
import com.fluxyBackend.service.BusinessClock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Resultados de campañas. Un pedido cuenta con el estado que tiene hoy: si se canceló después,
 * deja de sumar. "Ventas" son los pedidos ya confirmados (la misma regla que Métricas);
 * los pendientes se informan aparte.
 */
@Service
@RequiredArgsConstructor
public class CampaignAnalyticsService {

    /** Desde cuándo se cuenta "todo el historial". */
    static final LocalDateTime BEGINNING = LocalDateTime.of(2000, 1, 1, 0, 0);
    private static final int MAX_DAYS_FILLED = 120;
    private static final List<CampaignEventType> FUNNEL = List.of(CampaignEventType.VIEW, CampaignEventType.PRODUCT_VIEW,
            CampaignEventType.ADD_TO_CART, CampaignEventType.CHECKOUT_STARTED, CampaignEventType.ORDER_COMPLETED);

    private final CampaignEventRepository events;
    private final MarketingOrderQueries orderQueries;
    private final CouponRepository coupons;
    private final BusinessClock clock;

    /** Totales de cada campaña de la empresa en el rango. */
    @Transactional(readOnly = true)
    public Map<Long, CampaignMetrics> metricsByCampaign(Long companyId, LocalDateTime from, LocalDateTime to) {
        Map<Long, long[]> visits = new HashMap<>();
        for (CampaignEventRepository.TypeCount row : events.countsByCampaign(companyId, from, to)) {
            if (row.getType() == CampaignEventType.VIEW) {
                visits.put(row.getCampaignId(), new long[]{row.getEvents(), row.getSessions()});
            }
        }
        Map<Long, Accumulator> orders = new HashMap<>();
        for (CampaignEventRepository.OrderCount row : events.ordersByCampaign(companyId, CampaignEventType.ORDER_COMPLETED, from, to)) {
            orders.computeIfAbsent(row.getCampaignId(), k -> new Accumulator())
                    .add(row.getStatus(), row.getOrders(), row.getTotal());
        }
        Set<Long> ids = new HashSet<>(visits.keySet());
        ids.addAll(orders.keySet());
        Map<Long, CampaignMetrics> result = new HashMap<>();
        for (Long id : ids) {
            long[] v = visits.getOrDefault(id, new long[2]);
            result.put(id, orders.getOrDefault(id, new Accumulator()).metrics(v[0], v[1]));
        }
        return result;
    }

    /** Suma de todas las campañas; los visitantes pueden repetirse entre campañas. */
    public CampaignMetrics sum(Collection<CampaignMetrics> all) {
        long visits = 0, visitors = 0, orders = 0, cancelled = 0;
        double sales = 0, pending = 0;
        for (CampaignMetrics m : all) {
            visits += m.visits();
            visitors += m.visitors();
            orders += m.orders();
            cancelled += m.cancelledOrders();
            sales += m.sales();
            pending += m.pendingSales();
        }
        return CampaignMetrics.of(visits, visitors, orders, cancelled, sales, pending);
    }

    @Transactional(readOnly = true)
    public CampaignAnalytics analytics(MarketingCampaign campaign, LocalDateTime from, LocalDateTime to,
                                       boolean full, boolean canExport) {
        List<EventRow> rows = events.rows(campaign.getId(), from, to);
        Set<Long> orderIds = rows.stream().filter(r -> r.getType() == CampaignEventType.ORDER_COMPLETED)
                .map(EventRow::getOrderId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, CampaignEventRepository.OrderRow> orders = orderIds.isEmpty() ? Map.of()
                : events.orders(orderIds).stream().collect(Collectors.toMap(CampaignEventRepository.OrderRow::getId, o -> o));

        Accumulator totals = new Accumulator();
        orders.values().forEach(o -> totals.add(o.getStatus(), 1, o.getTotal()));
        List<EventRow> views = rows.stream().filter(r -> r.getType() == CampaignEventType.VIEW).toList();
        long visitors = views.stream().map(EventRow::getSessionId).filter(Objects::nonNull).distinct().count();
        CampaignMetrics metrics = totals.metrics(views.size(), visitors);

        List<CampaignAnalytics.FunnelStep> funnel = List.of();
        List<CampaignAnalytics.ChannelRow> channels = List.of();
        List<CampaignAnalytics.DayRow> daily = List.of();
        List<CampaignAnalytics.ProductRow> products = List.of();
        if (full) {
            funnel = funnel(rows, metrics.orders());
            channels = channels(rows, orders);
            daily = daily(rows, orders, from, to, clock.zone(campaign.getCompanyId()));
            List<Long> live = orders.values().stream().filter(o -> o.getStatus() != OrderStatus.CANCELLED)
                    .map(CampaignEventRepository.OrderRow::getId).toList();
            products = live.isEmpty() ? List.of() : events.products(live, OrderStatus.CANCELLED).stream().limit(5)
                    .map(p -> new CampaignAnalytics.ProductRow(p.getProductId(), p.getName(),
                            p.getUnits() == null ? 0 : p.getUnits(), round(p.getRevenue())))
                    .toList();
        }

        return new CampaignAnalytics(campaign.getId(), BusinessClock.withOffset(from), BusinessClock.withOffset(to),
                CampaignTrackingService.ATTRIBUTION_DAYS, full, canExport, metrics, funnel, channels, daily, products,
                coupon(campaign, orders.values(), from, to));
    }

    private List<CampaignAnalytics.FunnelStep> funnel(List<EventRow> rows, long orders) {
        Map<CampaignEventType, Long> sessions = rows.stream()
                .filter(r -> r.getSessionId() != null && r.getType() != CampaignEventType.ORDER_COMPLETED)
                .collect(Collectors.groupingBy(EventRow::getType,
                        Collectors.mapping(EventRow::getSessionId, Collectors.collectingAndThen(Collectors.toSet(), s -> (long) s.size()))));
        List<CampaignAnalytics.FunnelStep> steps = new ArrayList<>();
        long previous = -1;
        for (CampaignEventType type : FUNNEL) {
            long count = type == CampaignEventType.ORDER_COMPLETED ? orders : sessions.getOrDefault(type, 0L);
            double rate = previous < 0 ? 1 : previous == 0 ? 0 : Math.min(1.0, (double) count / previous);
            steps.add(new CampaignAnalytics.FunnelStep(type.name(), count, Math.round(rate * 10000) / 10000.0));
            previous = count;
        }
        return steps;
    }

    private List<CampaignAnalytics.ChannelRow> channels(List<EventRow> rows, Map<Long, CampaignEventRepository.OrderRow> orders) {
        Map<CampaignChannel, long[]> counts = new EnumMap<>(CampaignChannel.class);
        Map<CampaignChannel, Double> amounts = new EnumMap<>(CampaignChannel.class);
        for (EventRow row : rows) {
            CampaignChannel channel = row.getSource() == null ? CampaignChannel.DIRECT : row.getSource();
            if (row.getType() == CampaignEventType.VIEW) {
                counts.computeIfAbsent(channel, k -> new long[2])[0]++;
            } else if (row.getType() == CampaignEventType.ORDER_COMPLETED) {
                CampaignEventRepository.OrderRow order = orders.get(row.getOrderId());
                if (order == null || order.getStatus() == OrderStatus.CANCELLED) continue;
                counts.computeIfAbsent(channel, k -> new long[2])[1]++;
                amounts.merge(channel, value(order.getTotal()), Double::sum);
            }
        }
        return counts.entrySet().stream()
                .map(e -> new CampaignAnalytics.ChannelRow(e.getKey().name(), e.getValue()[0], e.getValue()[1],
                        round(amounts.getOrDefault(e.getKey(), 0.0))))
                .sorted(Comparator.comparingDouble(CampaignAnalytics.ChannelRow::amount).reversed()
                        .thenComparing(Comparator.comparingLong(CampaignAnalytics.ChannelRow::visits).reversed()))
                .toList();
    }

    private List<CampaignAnalytics.DayRow> daily(List<EventRow> rows, Map<Long, CampaignEventRepository.OrderRow> orders,
                                                 LocalDateTime from, LocalDateTime to, ZoneId zone) {
        TreeMap<LocalDate, double[]> days = new TreeMap<>();
        LocalDate first = clock.businessDate(from, zone);
        LocalDate last = clock.businessDate(to, zone);
        if (ChronoUnit.DAYS.between(first, last) <= MAX_DAYS_FILLED) {
            for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) days.put(d, new double[3]);
        }
        for (EventRow row : rows) {
            LocalDate day = clock.businessDate(row.getOccurredAt(), zone);
            if (row.getType() == CampaignEventType.VIEW) {
                days.computeIfAbsent(day, k -> new double[3])[0]++;
            } else if (row.getType() == CampaignEventType.ORDER_COMPLETED) {
                CampaignEventRepository.OrderRow order = orders.get(row.getOrderId());
                if (order == null || order.getStatus() == OrderStatus.CANCELLED) continue;
                double[] d = days.computeIfAbsent(day, k -> new double[3]);
                d[1]++;
                d[2] += value(order.getTotal());
            }
        }
        return days.entrySet().stream()
                .map(e -> new CampaignAnalytics.DayRow(e.getKey(), (long) e.getValue()[0], (long) e.getValue()[1], round(e.getValue()[2])))
                .toList();
    }

    private CampaignAnalytics.CouponResult coupon(MarketingCampaign campaign, Collection<CampaignEventRepository.OrderRow> attributed,
                                                  LocalDateTime from, LocalDateTime to) {
        if (campaign.getCouponId() == null) return null;
        Coupon coupon = coupons.findById(campaign.getCouponId()).orElse(null);
        if (coupon == null || coupon.getCompany() == null || !campaign.getCompanyId().equals(coupon.getCompany().getId())) return null;
        String code = coupon.getCode().toUpperCase(Locale.ROOT);
        // Mientras la campaña estuvo publicada, dentro del rango pedido.
        LocalDateTime start = max(from, campaign.getActivatedAt() == null ? campaign.getCreatedAt() : campaign.getActivatedAt());
        LocalDateTime end = min(to, campaign.getFinishedAt() == null ? to : campaign.getFinishedAt());
        long attributedOrders = attributed.stream()
                .filter(o -> o.getStatus() != OrderStatus.CANCELLED && o.getCouponCode() != null
                        && o.getCouponCode().equalsIgnoreCase(code))
                .count();
        if (!start.isBefore(end)) return new CampaignAnalytics.CouponResult(coupon.getId(), coupon.getCode(), 0, 0, 0, attributedOrders);
        MarketingOrderQueries.CouponUse use = orderQueries.couponUse(campaign.getCompanyId(), code, start, end, OrderStatus.CANCELLED);
        return new CampaignAnalytics.CouponResult(coupon.getId(), coupon.getCode(), use == null ? 0 : use.getOrders(),
                round(use == null ? null : use.getTotal()), round(use == null ? null : use.getDiscount()), attributedOrders);
    }

    private static LocalDateTime max(LocalDateTime a, LocalDateTime b) {
        return b == null || a.isAfter(b) ? a : b;
    }

    private static LocalDateTime min(LocalDateTime a, LocalDateTime b) {
        return b == null || a.isBefore(b) ? a : b;
    }

    private static double value(Double amount) {
        return amount == null ? 0 : amount;
    }

    private static double round(Double amount) {
        return amount == null ? 0 : Math.round(amount * 100) / 100.0;
    }

    /** Suma pedidos según su estado actual. */
    private static final class Accumulator {
        long orders;
        long cancelled;
        double sales;
        double pending;

        void add(OrderStatus status, long count, Double total) {
            double amount = value(total);
            if (status == OrderStatus.CANCELLED) {
                cancelled += count;
            } else if (status != null && status.isSale()) {
                orders += count;
                sales += amount;
            } else {
                orders += count;
                pending += amount;
            }
        }

        CampaignMetrics metrics(long visits, long visitors) {
            return CampaignMetrics.of(visits, visitors, orders, cancelled, sales, pending);
        }
    }
}
