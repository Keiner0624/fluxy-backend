package com.fluxyBackend.service;

import com.fluxyBackend.entity.Customer;
import com.fluxyBackend.entity.Order;
import com.fluxyBackend.entity.OrderItem;
import com.fluxyBackend.entity.OrderStatus;
import com.fluxyBackend.entity.Prodcut;
import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.repository.CustomerRepository;
import com.fluxyBackend.repository.OrderRepository;
import com.fluxyBackend.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resumen, métricas y reportes. Una venta es un pedido confirmado en adelante
 * (ver OrderStatus.SALE); los días se cuentan en la zona horaria del negocio.
 */
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private static final int MAX_RANGE_DAYS = 366;

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final CustomerRepository customerRepository;
    private final BusinessClock clock;

    // ─── Vistas ───────────────────────────────────────────────────────────────

    public record RecentOrder(Long id, OffsetDateTime createdAt, String customerName, String status, double total) {}

    public record LowStockItem(Long id, String name, int stock, int minStock, String imageUrl) {}

    public record Overview(double salesToday, double salesYesterday, double salesMonth, double salesPreviousMonth,
                           long ordersToday, long pendingOrders, long inProgressOrders, double averageTicketMonth,
                           long saleOrdersMonth, long newCustomersMonth, long newCustomersPreviousMonth,
                           long lowStockCount, long outOfStockCount, List<LowStockItem> lowStock,
                           List<RecentOrder> recentOrders, String timezone) {}

    public record SeriesPoint(String period, double sales, long orders, long saleOrders) {}

    public record StatusSlice(String status, long count) {}

    public record TopProduct(Long productId, String name, long units, double revenue) {}

    public record CategorySales(String category, long units, double revenue, double share) {}

    public record TopCustomer(Long customerId, String name, String phone, long orders, double revenue,
                              OffsetDateTime lastOrderAt) {}

    public record CancelledOrder(Long orderId, OffsetDateTime createdAt, String customerName, double total,
                                 String reason) {}

    public record Summary(LocalDate from, LocalDate to, double sales, long orders, long saleOrders,
                          double averageTicket, double conversionRate, long cancelledOrders, double cancelledAmount,
                          double discounts, long units, double previousSales, long previousSaleOrders) {}

    public record Metrics(Summary summary, List<SeriesPoint> series, List<StatusSlice> byStatus,
                          List<TopProduct> topProducts, long customers, long returningCustomers,
                          double repeatRate) {}

    public record ExportColumn(String key, String label, String type) {}

    public record Export(String title, LocalDate from, LocalDate to, List<ExportColumn> columns,
                         List<Map<String, Object>> rows) {}

    // ─── Resumen del inicio ───────────────────────────────────────────────────

    public Overview overview(Long companyId) {
        ZoneId zone = clock.zone(companyId);
        LocalDate today = clock.today(zone);
        LocalDate yesterday = today.minusDays(1);
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate previousMonthStart = monthStart.minusMonths(1);
        // Mismo tramo del mes anterior: al día 12 se compara contra el 1 al 12 del mes pasado.
        LocalDate previousSpanEnd = previousMonthStart.plusDays(today.getDayOfMonth());
        if (previousSpanEnd.isAfter(monthStart)) previousSpanEnd = monthStart;

        double salesToday = 0, salesYesterday = 0, salesMonth = 0, salesPrevious = 0;
        long ordersToday = 0, saleOrdersMonth = 0;
        for (Order o : orderRepository.findInRange(companyId, clock.startOf(previousMonthStart, zone),
                clock.startOf(today.plusDays(1), zone))) {
            if (o.getCreatedAt() == null) continue;
            LocalDate day = clock.businessDate(o.getCreatedAt(), zone);
            double total = total(o);
            boolean sale = isSale(o);
            if (day.equals(today)) {
                ordersToday++;
                if (sale) salesToday += total;
            }
            if (sale && day.equals(yesterday)) salesYesterday += total;
            if (sale && !day.isBefore(monthStart)) {
                salesMonth += total;
                saleOrdersMonth++;
            }
            if (sale && !day.isBefore(previousMonthStart) && day.isBefore(previousSpanEnd)) salesPrevious += total;
        }

        long pending = 0, inProgress = 0;
        for (OrderRepository.StatusCount row : orderRepository.countByStatus(companyId)) {
            if (row.getStatus() == OrderStatus.PENDING) pending += row.getTotal();
            if (row.getStatus() != null && OrderStatus.IN_PROGRESS.contains(row.getStatus())) inProgress += row.getTotal();
        }

        long newThisMonth = 0, newPrevious = 0;
        for (OrderRepository.CustomerStats stats : orderRepository.customerStats(companyId, OrderStatus.SALE)) {
            if (stats.getFirstOrderAt() == null) continue;
            LocalDate first = clock.businessDate(stats.getFirstOrderAt(), zone);
            if (!first.isBefore(monthStart)) newThisMonth++;
            else if (!first.isBefore(previousMonthStart) && first.isBefore(previousSpanEnd)) newPrevious++;
        }

        ProductRepository.Stats stock = productRepository.stats(companyId, Prodcut.Status.HIDDEN);
        List<LowStockItem> lowStock = productRepository.findLowStock(companyId, Prodcut.Status.HIDDEN, PageRequest.of(0, 5))
                .stream().map(p -> new LowStockItem(p.getId(), p.getName(), p.getStock(), p.getMinStock(), p.getImageUrl()))
                .toList();
        List<RecentOrder> recent = orderRepository.findTop5ByCompanyIdOrderByCreatedAtDescIdDesc(companyId).stream()
                .map(o -> new RecentOrder(o.getId(), BusinessClock.withOffset(o.getCreatedAt()), o.getCustomerName(),
                        OrderService.displayStatus(o.getStatus()).name(), total(o)))
                .toList();

        return new Overview(round(salesToday), round(salesYesterday), round(salesMonth), round(salesPrevious),
                ordersToday, pending, inProgress, saleOrdersMonth > 0 ? round(salesMonth / saleOrdersMonth) : 0,
                saleOrdersMonth, newThisMonth, newPrevious,
                stock.getLowStock() == null ? 0 : stock.getLowStock(),
                stock.getOutOfStock() == null ? 0 : stock.getOutOfStock(),
                lowStock, recent, zone.getId());
    }

    // ─── Métricas ─────────────────────────────────────────────────────────────

    public Metrics metrics(Long companyId, LocalDate from, LocalDate to) {
        ZoneId zone = clock.zone(companyId);
        LocalDate[] range = range(from, to, zone);
        List<Order> orders = detailedOrders(companyId, range[0], range[1], zone);
        Summary summary = summary(companyId, range[0], range[1], orders, zone);

        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (OrderStatus s : OrderStatus.FLOW) byStatus.put(s.name(), 0L);
        byStatus.put(OrderStatus.CANCELLED.name(), 0L);
        orders.forEach(o -> byStatus.merge(OrderService.displayStatus(o.getStatus()).name(), 1L, Long::sum));

        // Recurrente: ya había comprado antes del período o compró más de una vez en él.
        Map<Long, OrderRepository.CustomerStats> history = orderRepository.customerStats(companyId, OrderStatus.SALE)
                .stream().collect(Collectors.toMap(OrderRepository.CustomerStats::getCustomerId, Function.identity()));
        LocalDateTime rangeStart = clock.startOf(range[0], zone);
        Map<Long, Long> saleOrdersByCustomer = orders.stream().filter(this::isSale)
                .filter(o -> o.getCustomerId() != null)
                .collect(Collectors.groupingBy(Order::getCustomerId, Collectors.counting()));
        long returning = saleOrdersByCustomer.entrySet().stream().filter(e -> {
            OrderRepository.CustomerStats s = history.get(e.getKey());
            return e.getValue() > 1 || (s != null && s.getFirstOrderAt() != null && s.getFirstOrderAt().isBefore(rangeStart));
        }).count();
        long customers = saleOrdersByCustomer.size();

        return new Metrics(summary, series(orders, range[0], range[1], "day", zone),
                byStatus.entrySet().stream().map(e -> new StatusSlice(e.getKey(), e.getValue())).toList(),
                topProducts(orders, 5), customers, returning,
                customers > 0 ? round(returning * 100.0 / customers) : 0);
    }

    // ─── Reportes ─────────────────────────────────────────────────────────────

    public Summary reportSummary(Long companyId, LocalDate from, LocalDate to) {
        ZoneId zone = clock.zone(companyId);
        LocalDate[] range = range(from, to, zone);
        return summary(companyId, range[0], range[1], detailedOrders(companyId, range[0], range[1], zone), zone);
    }

    public List<SeriesPoint> salesSeries(Long companyId, LocalDate from, LocalDate to, String groupBy) {
        ZoneId zone = clock.zone(companyId);
        LocalDate[] range = range(from, to, zone);
        return series(detailedOrders(companyId, range[0], range[1], zone), range[0], range[1], groupBy, zone);
    }

    public List<CategorySales> salesByCategory(Long companyId, LocalDate from, LocalDate to) {
        ZoneId zone = clock.zone(companyId);
        LocalDate[] range = range(from, to, zone);
        Map<String, double[]> totals = new LinkedHashMap<>();
        double revenue = 0;
        for (Order o : detailedOrders(companyId, range[0], range[1], zone)) {
            if (!isSale(o) || o.getItems() == null) continue;
            for (OrderItem item : o.getItems()) {
                String name = item.getProdcut().getCategory() == null ? "Sin categoría" : item.getProdcut().getCategory().getName();
                double[] t = totals.computeIfAbsent(name, k -> new double[2]);
                t[0] += item.getQuantity();
                t[1] += subtotal(item);
                revenue += subtotal(item);
            }
        }
        double all = revenue;
        return totals.entrySet().stream()
                .map(e -> new CategorySales(e.getKey(), (long) e.getValue()[0], round(e.getValue()[1]),
                        all > 0 ? round(e.getValue()[1] * 100 / all) : 0))
                .sorted(Comparator.comparingDouble(CategorySales::revenue).reversed())
                .toList();
    }

    public List<TopProduct> topProducts(Long companyId, LocalDate from, LocalDate to, int limit) {
        ZoneId zone = clock.zone(companyId);
        LocalDate[] range = range(from, to, zone);
        return topProducts(detailedOrders(companyId, range[0], range[1], zone), limit);
    }

    public List<TopCustomer> topCustomers(Long companyId, LocalDate from, LocalDate to, int limit) {
        ZoneId zone = clock.zone(companyId);
        LocalDate[] range = range(from, to, zone);
        record Acc(long[] orders, double[] revenue, LocalDateTime[] last) {}
        Map<Long, Acc> acc = new HashMap<>();
        for (Order o : detailedOrders(companyId, range[0], range[1], zone)) {
            if (!isSale(o) || o.getCustomerId() == null) continue;
            Acc a = acc.computeIfAbsent(o.getCustomerId(),
                    k -> new Acc(new long[1], new double[1], new LocalDateTime[1]));
            a.orders()[0]++;
            a.revenue()[0] += total(o);
            if (a.last()[0] == null || o.getCreatedAt().isAfter(a.last()[0])) a.last()[0] = o.getCreatedAt();
        }
        Map<Long, Customer> customers = customerRepository.findAllById(acc.keySet()).stream()
                .collect(Collectors.toMap(Customer::getId, Function.identity()));
        return acc.entrySet().stream()
                .map(e -> {
                    Customer c = customers.get(e.getKey());
                    return new TopCustomer(e.getKey(), c == null ? "Cliente" : c.getName(), c == null ? null : c.getPhone(),
                            e.getValue().orders()[0], round(e.getValue().revenue()[0]),
                            BusinessClock.withOffset(e.getValue().last()[0]));
                })
                .sorted(Comparator.comparingLong(TopCustomer::orders).thenComparingDouble(TopCustomer::revenue).reversed())
                .limit(clampLimit(limit))
                .toList();
    }

    public List<CancelledOrder> cancelledOrders(Long companyId, LocalDate from, LocalDate to) {
        ZoneId zone = clock.zone(companyId);
        LocalDate[] range = range(from, to, zone);
        return orderRepository.findInRange(companyId, clock.startOf(range[0], zone), clock.startOf(range[1].plusDays(1), zone))
                .stream().filter(o -> o.getStatus() == OrderStatus.CANCELLED)
                .sorted(Comparator.comparing(Order::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(o -> new CancelledOrder(o.getId(), BusinessClock.withOffset(o.getCreatedAt()), o.getCustomerName(),
                        total(o), o.getCancelReason()))
                .toList();
    }

    /** Filas listas para exportar a CSV, Excel o PDF. */
    public Export export(Long companyId, String type, LocalDate from, LocalDate to) {
        ZoneId zone = clock.zone(companyId);
        LocalDate[] range = range(from, to, zone);
        LocalDate f = range[0], t = range[1];
        return switch (type == null ? "" : type) {
            case "sales" -> new Export("Ventas por día", f, t,
                    List.of(col("period", "Fecha", "text"), col("saleOrders", "Ventas", "number"),
                            col("orders", "Pedidos recibidos", "number"), col("sales", "Monto vendido", "money")),
                    rows(salesSeries(companyId, f, t, "day")));
            case "categories" -> new Export("Ventas por categoría", f, t,
                    List.of(col("category", "Categoría", "text"), col("units", "Unidades", "number"),
                            col("revenue", "Monto vendido", "money"), col("share", "Participación %", "number")),
                    rows(salesByCategory(companyId, f, t)));
            case "products" -> new Export("Productos más vendidos", f, t,
                    List.of(col("name", "Producto", "text"), col("units", "Unidades", "number"),
                            col("revenue", "Monto vendido", "money")),
                    rows(topProducts(companyId, f, t, 100)));
            case "customers" -> new Export("Clientes frecuentes", f, t,
                    List.of(col("name", "Cliente", "text"), col("phone", "Teléfono", "text"),
                            col("orders", "Compras", "number"), col("revenue", "Monto comprado", "money"),
                            col("lastOrderAt", "Última compra", "date")),
                    rows(topCustomers(companyId, f, t, 100)));
            case "cancelled" -> new Export("Pedidos cancelados", f, t,
                    List.of(col("orderId", "Pedido", "number"), col("createdAt", "Fecha", "date"),
                            col("customerName", "Cliente", "text"), col("total", "Monto", "money"),
                            col("reason", "Motivo", "text")),
                    rows(cancelledOrders(companyId, f, t)));
            default -> throw new BusinessException("Reporte desconocido. Usá sales, categories, products, customers o cancelled.");
        };
    }

    // ─── Cálculo ──────────────────────────────────────────────────────────────

    private Summary summary(Long companyId, LocalDate from, LocalDate to, List<Order> orders, ZoneId zone) {
        double sales = 0, cancelledAmount = 0, discounts = 0;
        long saleOrders = 0, cancelled = 0, units = 0;
        for (Order o : orders) {
            if (isSale(o)) {
                sales += total(o);
                saleOrders++;
                discounts += o.getDiscountAmount() == null ? 0 : o.getDiscountAmount();
                if (o.getItems() != null) units += o.getItems().stream().mapToLong(OrderItem::getQuantity).sum();
            } else if (o.getStatus() == OrderStatus.CANCELLED) {
                cancelled++;
                cancelledAmount += total(o);
            }
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        LocalDate previousFrom = from.minusDays(days);
        double previousSales = 0;
        long previousSaleOrders = 0;
        for (Order o : orderRepository.findInRange(companyId, clock.startOf(previousFrom, zone), clock.startOf(from, zone))) {
            if (isSale(o)) {
                previousSales += total(o);
                previousSaleOrders++;
            }
        }
        return new Summary(from, to, round(sales), orders.size(), saleOrders,
                saleOrders > 0 ? round(sales / saleOrders) : 0,
                orders.isEmpty() ? 0 : round(saleOrders * 100.0 / orders.size()),
                cancelled, round(cancelledAmount), round(discounts), units, round(previousSales), previousSaleOrders);
    }

    private List<SeriesPoint> series(List<Order> orders, LocalDate from, LocalDate to, String groupBy, ZoneId zone) {
        String unit = switch (groupBy == null ? "day" : groupBy) {
            case "week", "month" -> groupBy;
            default -> "day";
        };
        Map<LocalDate, double[]> buckets = new TreeMap<>();
        for (LocalDate d = bucket(from, unit); !d.isAfter(to); d = next(d, unit)) {
            buckets.put(d, new double[3]);
        }
        for (Order o : orders) {
            if (o.getCreatedAt() == null) continue;
            double[] b = buckets.computeIfAbsent(bucket(clock.businessDate(o.getCreatedAt(), zone), unit), k -> new double[3]);
            b[1]++;
            if (isSale(o)) {
                b[0] += total(o);
                b[2]++;
            }
        }
        return buckets.entrySet().stream()
                .map(e -> new SeriesPoint("month".equals(unit) ? e.getKey().toString().substring(0, 7) : e.getKey().toString(),
                        round(e.getValue()[0]), (long) e.getValue()[1], (long) e.getValue()[2]))
                .toList();
    }

    private List<TopProduct> topProducts(List<Order> orders, int limit) {
        Map<Long, Object[]> acc = new HashMap<>();
        for (Order o : orders) {
            if (!isSale(o) || o.getItems() == null) continue;
            for (OrderItem item : o.getItems()) {
                Object[] a = acc.computeIfAbsent(item.getProdcut().getId(),
                        k -> new Object[]{item.getProdcut().getName(), 0L, 0.0});
                a[1] = (long) a[1] + item.getQuantity();
                a[2] = (double) a[2] + subtotal(item);
            }
        }
        return acc.entrySet().stream()
                .map(e -> new TopProduct(e.getKey(), (String) e.getValue()[0], (long) e.getValue()[1],
                        round((double) e.getValue()[2])))
                .sorted(Comparator.comparingLong(TopProduct::units).thenComparingDouble(TopProduct::revenue).reversed())
                .limit(clampLimit(limit))
                .toList();
    }

    private List<Order> detailedOrders(Long companyId, LocalDate from, LocalDate to, ZoneId zone) {
        return orderRepository.findDetailedInRange(companyId, clock.startOf(from, zone), clock.startOf(to.plusDays(1), zone));
    }

    /** Rango inclusivo, por defecto los últimos 30 días. */
    private LocalDate[] range(LocalDate from, LocalDate to, ZoneId zone) {
        LocalDate end = to == null ? clock.today(zone) : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (start.isAfter(end)) throw new BusinessException("La fecha inicial no puede ser posterior a la final.");
        if (ChronoUnit.DAYS.between(start, end) >= MAX_RANGE_DAYS) {
            throw new BusinessException("El período puede abarcar hasta " + MAX_RANGE_DAYS + " días.");
        }
        return new LocalDate[]{start, end};
    }

    private static LocalDate bucket(LocalDate day, String unit) {
        return switch (unit) {
            case "week" -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case "month" -> day.withDayOfMonth(1);
            default -> day;
        };
    }

    private static LocalDate next(LocalDate day, String unit) {
        return switch (unit) {
            case "week" -> day.plusWeeks(1);
            case "month" -> day.plusMonths(1);
            default -> day.plusDays(1);
        };
    }

    private boolean isSale(Order o) {
        return o.getStatus() != null && o.getStatus().isSale();
    }

    private static double total(Order o) {
        return o.getTotal() == null ? 0 : o.getTotal();
    }

    private static double subtotal(OrderItem item) {
        return item.getSubTotal() == null ? 0 : item.getSubTotal();
    }

    private static int clampLimit(int limit) {
        return Math.min(Math.max(limit, 1), 100);
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private static ExportColumn col(String key, String label, String type) {
        return new ExportColumn(key, label, type);
    }

    /** Convierte records en mapas por nombre de componente. */
    private static List<Map<String, Object>> rows(List<? extends Record> records) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Record r : records) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (var component : r.getClass().getRecordComponents()) {
                try {
                    row.put(component.getName(), component.getAccessor().invoke(r));
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
            rows.add(row);
        }
        return rows;
    }
}
