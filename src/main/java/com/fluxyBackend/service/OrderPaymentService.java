package com.fluxyBackend.service;

import com.fluxyBackend.DTOs.PageResponse;
import com.fluxyBackend.entity.Order;
import com.fluxyBackend.entity.OrderPayment;
import com.fluxyBackend.entity.OrderPayment.Provider;
import com.fluxyBackend.entity.OrderPayment.Status;
import com.fluxyBackend.entity.OrderStatus;
import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.exception.NotFoundException;
import com.fluxyBackend.repository.OrderPaymentRepository;
import com.fluxyBackend.repository.OrderRepository;
import com.fluxyBackend.security.access.Member;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Cobros de pedidos y su conciliación contra lo vendido. */
@Service
@RequiredArgsConstructor
public class OrderPaymentService {

    /** Tolerancia para comparar montos con decimales. */
    private static final double CENT = 0.009;
    private static final int MAX_ISSUES = 200;

    private final OrderPaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final BusinessClock clock;
    private final org.springframework.context.ApplicationEventPublisher events;

    public record PaymentView(Long id, Long orderId, String status, String provider, String method, double amount,
                              double refundedAmount, double netAmount, String currency, String providerReference,
                              String note, String createdBy, OffsetDateTime createdAt, OffsetDateTime approvedAt,
                              String customerName, String orderStatus) {}

    public record Summary(double approvedAmount, long approvedCount, double pendingAmount, long pendingCount,
                          double rejectedAmount, long rejectedCount, double refundedAmount, long refundedCount,
                          double netCollected) {}

    public record RegisterRequest(Long orderId, String method, Double amount, String status, String provider,
                                  String providerReference, String note) {}

    public record UpdateRequest(String status, String method, String providerReference, String note) {}

    public record RefundRequest(Double amount, String reason) {}

    public record ReconciliationIssue(String type, Long orderId, OffsetDateTime orderDate, String customerName,
                                      String orderStatus, double orderTotal, double collected, double difference,
                                      long pendingPayments) {}

    public record Reconciliation(long unpaidCount, double unpaidAmount, long refundDueCount, double refundDueAmount,
                                 long overpaidCount, double overpaidAmount, List<ReconciliationIssue> issues) {}

    // ─── Estado de cobro de un pedido ─────────────────────────────────────────

    /** PAID, PARTIAL, PENDING, REFUNDED o UNPAID, según los pagos del pedido. */
    public static String paymentStatus(double orderTotal, Collection<OrderPayment> payments) {
        double net = payments.stream().mapToDouble(OrderPayment::netAmount).sum();
        boolean refunded = payments.stream().anyMatch(p -> p.getRefundedAmount() != null && p.getRefundedAmount() > 0);
        if (refunded && net <= CENT) return "REFUNDED";
        if (orderTotal > 0 && net >= orderTotal - CENT) return "PAID";
        if (net > CENT) return "PARTIAL";
        if (payments.stream().anyMatch(p -> p.getStatus() == Status.PENDING)) return "PENDING";
        return "UNPAID";
    }

    public static double collected(Collection<OrderPayment> payments) {
        return payments.stream().mapToDouble(OrderPayment::netAmount).sum();
    }

    // ─── Consultas ────────────────────────────────────────────────────────────

    public PageResponse<PaymentView> list(Long companyId, String status, String method, String query,
                                          LocalDate from, LocalDate to, int page, int size) {
        Specification<OrderPayment> spec = filters(companyId, status, method, query, from, to);
        Page<OrderPayment> result = paymentRepository.findAll(spec, PageRequest.of(Math.max(page, 0),
                PageResponse.clampSize(size), Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
        Map<Long, Order> orders = ordersById(result.getContent().stream().map(OrderPayment::getOrderId).toList());
        return PageResponse.of(result, p -> view(p, orders.get(p.getOrderId())));
    }

    public Summary summary(Long companyId, LocalDate from, LocalDate to) {
        List<OrderPayment> payments = paymentRepository.findAll(filters(companyId, null, null, null, from, to));
        double approved = 0, pending = 0, rejected = 0, refunded = 0, net = 0;
        long approvedCount = 0, pendingCount = 0, rejectedCount = 0, refundedCount = 0;
        for (OrderPayment p : payments) {
            switch (p.getStatus()) {
                case APPROVED -> { approved += p.getAmount(); approvedCount++; }
                case PENDING -> { pending += p.getAmount(); pendingCount++; }
                case REJECTED -> { rejected += p.getAmount(); rejectedCount++; }
                case REFUNDED -> { approved += p.getAmount(); approvedCount++; }
            }
            if (p.getRefundedAmount() != null && p.getRefundedAmount() > 0) {
                refunded += p.getRefundedAmount();
                refundedCount++;
            }
            net += p.netAmount();
        }
        return new Summary(round(approved), approvedCount, round(pending), pendingCount, round(rejected),
                rejectedCount, round(refunded), refundedCount, round(net));
    }

    public List<PaymentView> forOrder(Order order) {
        return paymentRepository.findByOrderIdOrderByCreatedAtAscIdAsc(order.getId()).stream()
                .map(p -> view(p, order)).toList();
    }

    /**
     * Diferencias entre lo vendido y lo cobrado: ventas sin cobrar, pedidos
     * cancelados con dinero que devolver y cobros de más.
     */
    public Reconciliation reconciliation(Long companyId) {
        Map<Long, List<OrderPayment>> paymentsByOrder = paymentRepository.findByCompanyId(companyId).stream()
                .collect(Collectors.groupingBy(OrderPayment::getOrderId));
        List<ReconciliationIssue> issues = new ArrayList<>();
        long unpaid = 0, refundDue = 0, overpaid = 0;
        double unpaidAmount = 0, refundDueAmount = 0, overpaidAmount = 0;

        for (Order order : orderRepository.findByCompanyId(companyId)) {
            OrderStatus status = order.getStatus() == null ? OrderStatus.PENDING : order.getStatus();
            if (status == OrderStatus.PENDING) continue;
            List<OrderPayment> payments = paymentsByOrder.getOrDefault(order.getId(), List.of());
            double total = order.getTotal() == null ? 0 : order.getTotal();
            double collected = collected(payments);
            long pendingPayments = payments.stream().filter(p -> p.getStatus() == Status.PENDING).count();

            String type = null;
            double difference = 0;
            if (status == OrderStatus.CANCELLED) {
                if (collected > CENT) {
                    type = "REFUND_DUE";
                    difference = collected;
                    refundDue++;
                    refundDueAmount += collected;
                }
            } else if (collected < total - CENT) {
                type = "UNPAID";
                difference = total - collected;
                unpaid++;
                unpaidAmount += difference;
            } else if (collected > total + CENT) {
                type = "OVERPAID";
                difference = collected - total;
                overpaid++;
                overpaidAmount += difference;
            }
            if (type != null) {
                issues.add(new ReconciliationIssue(type, order.getId(), BusinessClock.withOffset(order.getCreatedAt()),
                        order.getCustomerName(), status.name(), round(total), round(collected), round(difference),
                        pendingPayments));
            }
        }
        issues.sort(Comparator.comparing(ReconciliationIssue::orderDate,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return new Reconciliation(unpaid, round(unpaidAmount), refundDue, round(refundDueAmount), overpaid,
                round(overpaidAmount), issues.size() > MAX_ISSUES ? issues.subList(0, MAX_ISSUES) : issues);
    }

    // ─── Escritura ────────────────────────────────────────────────────────────

    @Transactional
    public PaymentView register(Member member, RegisterRequest request) {
        if (request.orderId() == null) throw new BusinessException("Indicá el pedido al que corresponde el pago.");
        // Bloquear el pedido serializa los pagos que se registran sobre él.
        Order order = orderRepository.findByIdAndCompanyIdForUpdate(request.orderId(), member.companyId())
                .orElseThrow(() -> new NotFoundException("Pedido no encontrado"));
        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new BusinessException("No se pueden registrar pagos en un pedido cancelado.");
        }
        double amount = validAmount(request.amount());
        Status status = request.status() == null ? Status.APPROVED : parseStatus(request.status());
        if (status != Status.APPROVED && status != Status.PENDING) {
            throw new BusinessException("Un pago nuevo solo puede registrarse como aprobado o pendiente.");
        }
        if (status == Status.APPROVED) ensureNotOverpaid(order, amount);

        OrderPayment payment = new OrderPayment();
        payment.setCompanyId(member.companyId());
        payment.setOrderId(order.getId());
        payment.setStatus(status);
        payment.setProvider(parseProvider(request.provider()));
        payment.setMethod(method(request.method()));
        payment.setAmount(amount);
        payment.setProviderReference(trim(request.providerReference(), 120));
        payment.setNote(trim(request.note(), 300));
        payment.setCreatedBy(member.displayName());
        if (status == Status.APPROVED) payment.setApprovedAt(LocalDateTime.now());
        PaymentView saved = view(paymentRepository.save(payment), order);
        if (status == Status.APPROVED) events.publishEvent(new PaymentApprovedEvent(order.getId(), member.companyId()));
        return saved;
    }

    @Transactional
    public PaymentView update(Member member, Long paymentId, UpdateRequest request) {
        OrderPayment payment = paymentRepository.findByIdAndCompanyIdForUpdate(paymentId, member.companyId())
                .orElseThrow(() -> new NotFoundException("Pago no encontrado"));
        Order order = orderRepository.findByIdAndCompanyIdForUpdate(payment.getOrderId(), member.companyId())
                .orElseThrow(() -> new NotFoundException("Pedido no encontrado"));

        if (request.status() != null) {
            Status target = parseStatus(request.status());
            if (target != payment.getStatus()) {
                if (payment.getStatus() != Status.PENDING || (target != Status.APPROVED && target != Status.REJECTED)) {
                    throw new BusinessException("Solo un pago pendiente puede aprobarse o rechazarse. "
                            + "Para devolver dinero de un pago aprobado usá el reembolso.");
                }
                if (target == Status.APPROVED) {
                    if (order.getStatus() == OrderStatus.CANCELLED) {
                        throw new BusinessException("El pedido está cancelado: no se puede aprobar el pago.");
                    }
                    ensureNotOverpaid(order, payment.getAmount());
                    payment.setApprovedAt(LocalDateTime.now());
                    events.publishEvent(new PaymentApprovedEvent(order.getId(), member.companyId()));
                }
                payment.setStatus(target);
            }
        }
        if (request.method() != null) payment.setMethod(method(request.method()));
        if (request.providerReference() != null) payment.setProviderReference(trim(request.providerReference(), 120));
        if (request.note() != null) payment.setNote(trim(request.note(), 300));
        return view(paymentRepository.save(payment), order);
    }

    @Transactional
    public PaymentView refund(Member member, Long paymentId, RefundRequest request) {
        OrderPayment payment = paymentRepository.findByIdAndCompanyIdForUpdate(paymentId, member.companyId())
                .orElseThrow(() -> new NotFoundException("Pago no encontrado"));
        if (payment.getStatus() != Status.APPROVED) {
            throw new BusinessException("Solo se puede reembolsar un pago aprobado que no esté reembolsado del todo.");
        }
        double remaining = payment.getAmount() - payment.getRefundedAmount();
        double amount = request.amount() == null ? remaining : validAmount(request.amount());
        if (amount > remaining + CENT) {
            throw new BusinessException("El reembolso supera lo cobrado en este pago (S/ %.2f).".formatted(remaining));
        }
        String reason = trim(request.reason(), 200);
        if (reason == null) throw new BusinessException("Indicá el motivo del reembolso.");

        payment.setRefundedAmount(round(payment.getRefundedAmount() + amount));
        if (payment.getAmount() - payment.getRefundedAmount() <= CENT) payment.setStatus(Status.REFUNDED);
        String entry = "Reembolso S/ %.2f: %s (%s)".formatted(amount, reason, member.displayName());
        payment.setNote(trim(payment.getNote() == null ? entry : payment.getNote() + " · " + entry, 300));
        Order order = orderRepository.findByIdAndCompanyId(payment.getOrderId(), member.companyId()).orElse(null);
        return view(paymentRepository.save(payment), order);
    }

    // ─── Apoyo ────────────────────────────────────────────────────────────────

    private void ensureNotOverpaid(Order order, double amount) {
        double total = order.getTotal() == null ? 0 : order.getTotal();
        double collected = collected(paymentRepository.findByOrderIdOrderByCreatedAtAscIdAsc(order.getId()));
        double outstanding = total - collected;
        if (amount > outstanding + CENT) {
            throw new BusinessException(outstanding <= CENT
                    ? "Este pedido ya está cobrado por completo."
                    : "El pago supera lo que falta cobrar del pedido (S/ %.2f).".formatted(outstanding));
        }
    }

    private Specification<OrderPayment> filters(Long companyId, String status, String method, String query,
                                                LocalDate from, LocalDate to) {
        ZoneId zone = clock.zone(companyId);
        return (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("companyId"), companyId));
            if (status != null && !status.isBlank()) predicates.add(cb.equal(root.get("status"), parseStatus(status)));
            if (method != null && !method.isBlank()) predicates.add(cb.equal(root.get("method"), method));
            if (query != null && !query.isBlank()) {
                String digits = query.replace("#", "").strip();
                List<Predicate> any = new ArrayList<>();
                any.add(cb.like(cb.lower(root.get("providerReference")), "%" + query.strip().toLowerCase() + "%"));
                if (digits.matches("\\d{1,18}")) any.add(cb.equal(root.get("orderId"), Long.parseLong(digits)));
                predicates.add(cb.or(any.toArray(Predicate[]::new)));
            }
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), clock.startOf(from, zone)));
            if (to != null) predicates.add(cb.lessThan(root.get("createdAt"), clock.startOf(to.plusDays(1), zone)));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private Map<Long, Order> ordersById(Collection<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        return orderRepository.findAllById(new HashSet<>(ids)).stream()
                .collect(Collectors.toMap(Order::getId, Function.identity()));
    }

    static PaymentView view(OrderPayment p, Order order) {
        return new PaymentView(p.getId(), p.getOrderId(), p.getStatus().name(), p.getProvider().name(),
                p.getMethod(), p.getAmount(), p.getRefundedAmount() == null ? 0 : p.getRefundedAmount(),
                round(p.netAmount()), p.getCurrency(), p.getProviderReference(), p.getNote(), p.getCreatedBy(),
                BusinessClock.withOffset(p.getCreatedAt()), BusinessClock.withOffset(p.getApprovedAt()),
                order == null ? null : order.getCustomerName(),
                order == null || order.getStatus() == null ? null : order.getStatus().name());
    }

    private static Status parseStatus(String value) {
        try {
            return Status.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException("Estado de pago inválido: " + value);
        }
    }

    private static Provider parseProvider(String value) {
        if (value == null || value.isBlank()) return Provider.MANUAL;
        try {
            return Provider.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Proveedor de pago inválido: " + value);
        }
    }

    static String method(String value) {
        if (value == null || value.isBlank()) return null;
        String key = value.strip().toLowerCase(Locale.ROOT);
        if (!key.matches("^[a-z0-9_-]{1,40}$")) throw new BusinessException("Medio de pago inválido: " + value);
        return key;
    }

    private static double validAmount(Double amount) {
        if (amount == null || !Double.isFinite(amount) || amount <= 0 || amount > 10_000_000) {
            throw new BusinessException("El monto tiene que ser mayor que cero.");
        }
        return round(amount);
    }

    static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private static String trim(String value, int max) {
        if (value == null) return null;
        String t = value.strip();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }
}
