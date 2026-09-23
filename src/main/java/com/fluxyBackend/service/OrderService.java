package com.fluxyBackend.service;

import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.exception.NotFoundException;

import com.fluxyBackend.DTOs.*;
import com.fluxyBackend.entity.*;
import com.fluxyBackend.repository.*;
import com.fluxyBackend.response.OrderItemResponse;
import com.fluxyBackend.response.OrderRespose;
import com.fluxyBackend.response.ProductResponse;
import com.fluxyBackend.security.access.AccessService;
import com.fluxyBackend.security.access.Member;
import com.fluxyBackend.security.access.Permission;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final WhatsAppService whatsAppService;
    private final CouponRepository couponRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final CustomerService customerService;
    private final InventoryService inventoryService;
    private final IntegrationService integrationService;
    private final OrderStatusChangeRepository statusChangeRepository;
    private final OrderPaymentRepository paymentRepository;
    private final OrderPaymentService paymentService;
    private final OrderItemRepository orderItemRepository;
    private final BusinessClock clock;


    // ─── Vistas ───────────────────────────────────────────────────────────────

    public record OrderRow(Long id, OffsetDateTime createdAt, Long customerId, String customerName,
                           String customerPhone, long lines, long units, double total, double discount,
                           String couponCode, String status, String paymentMethod, double paidAmount,
                           String paymentStatus) {}

    public record OrderItemView(Long productId, String productName, int quantity, double unitPrice,
                                double subtotal) {}

    public record StatusChangeView(String fromStatus, String toStatus, String note, String changedBy,
                                   OffsetDateTime changedAt) {}

    public record OrderDetail(Long id, OffsetDateTime createdAt, OffsetDateTime updatedAt, String status,
                              List<String> nextStatuses, Long customerId, String customerName, String customerPhone,
                              String customerAddress, double subtotal, double discount, String couponCode,
                              double total, String paymentMethod, String cancelReason, double paidAmount,
                              String paymentStatus, List<OrderItemView> items, List<StatusChangeView> history,
                              List<OrderPaymentService.PaymentView> payments) {}

    public record StatusRequest(String status, String note) {}

    private User getUserByEmail(String email) {
        return userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    // ─── Creación ─────────────────────────────────────────────────────────────

    /** Pedido cargado desde el panel por alguien del equipo. */
    @Transactional
    public Order createOrder(CreateOrderRequest request, Member member) {
        return placeOrder(member.company(), request, member.user(), member.displayName(),
                "Pedido registrado desde el panel");
    }

    @Transactional
    public Order createOrder(CreateOrderRequest request, String email) {
        User user = getUserByEmail(email);
        String name = user.getFullName() == null ? user.getEmail() : user.getFullName();
        return placeOrder(user.getCompany(), request, user, name, "Pedido registrado desde el panel");
    }

    @Transactional
    public Order createOrderAsClient(CreateOrderRequest request, Company company) {
        return placeOrder(company, request, null, "Tienda en línea", "Pedido recibido desde la tienda");
    }

    private Order placeOrder(Company company, CreateOrderRequest request, User owner, String actor, String note) {
        // Serializa los pedidos de una tienda: dos pedidos simultáneos del mismo
        // teléfono crearían al cliente dos veces.
        Company lockedCompany = companyRepository.findByIdForUpdate(company.getId())
                .orElseThrow(() -> new NotFoundException("Empresa no encontrada"));

        Order order = new Order();
        order.setCustomerName(request.customerName);
        order.setCustomerPhone(request.customerPhone);
        order.setCustomerAddress(request.customerAddress);
        order.setCreatedAt(LocalDateTime.now());
        order.setStatus(OrderStatus.PENDING);
        order.setOwner(owner);
        order.setCompany(lockedCompany);
        order.setPaymentMethod(OrderPaymentService.method(request.paymentMethod));

        List<OrderItem> orderItems = new ArrayList<>();
        double total = 0.0;

        for (OrderItemsRequest itemsRequest : request.items) {
            Prodcut prodcut = productRepository.findByIdAndCompanyForUpdate(itemsRequest.productId, lockedCompany)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Producto no encontrado"));

            if (prodcut.getStatus() == Prodcut.Status.HIDDEN) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "El producto " + prodcut.getName() + " ya no está disponible");
            }
            if (prodcut.getStock() < itemsRequest.quantity) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stock insuficiente: " + prodcut.getName());
            }

            double subTotal = prodcut.getPrice() * itemsRequest.quantity;
            OrderItem orderItem = new OrderItem();
            orderItem.setProdcut(prodcut);
            orderItem.setQuantity(itemsRequest.quantity);
            orderItem.setUnitPrice(prodcut.getPrice());
            orderItem.setSubTotal(subTotal);
            orderItem.setOrder(order);
            orderItems.add(orderItem);
            total += subTotal;
        }

        order.setItems(orderItems);
        order.setTotal(total);
        applyCoupon(request.couponCode, lockedCompany, order, total);
        order.setCustomer(customerService.resolveForOrder(lockedCompany.getId(), request.customerName,
                request.customerPhone, request.customerAddress));

        Order savedOrder = orderRepository.save(order);
        String reference = "Pedido #" + savedOrder.getId();
        for (OrderItem item : orderItems) {
            // Si el mismo producto viene en dos líneas, apply valida el stock acumulado.
            inventoryService.apply(item.getProdcut(), InventoryMovement.Type.SALE, -item.getQuantity(),
                    null, reference, savedOrder.getId(), actor);
        }
        statusChangeRepository.save(new OrderStatusChange(savedOrder.getId(), lockedCompany.getId(),
                null, OrderStatus.PENDING, note, actor));

        if (savedOrder.getTotal() != null && savedOrder.getTotal() > 0) {
            OrderPayment payment = new OrderPayment();
            payment.setCompanyId(lockedCompany.getId());
            payment.setOrderId(savedOrder.getId());
            payment.setStatus(OrderPayment.Status.PENDING);
            payment.setProvider(OrderPayment.Provider.MANUAL);
            payment.setMethod(savedOrder.getPaymentMethod());
            payment.setAmount(OrderPaymentService.round(savedOrder.getTotal()));
            payment.setCreatedBy(actor);
            payment.setNote("Cobro esperado del pedido");
            paymentRepository.save(payment);
        }

        eventPublisher.publishEvent(new OrderCreatedEvent(savedOrder.getId(), lockedCompany.getId()));
        return savedOrder;
    }

    private void applyCoupon(String couponCode, Company company, Order order, double baseTotal) {
        if (couponCode == null || couponCode.isBlank()) {
            return;
        }

        // Los cupones son del plan Pro: con el plan vencido dejan de aplicarse (se conservan).
        if (!com.fluxyBackend.billing.PlanCatalog.has(company, com.fluxyBackend.billing.Feature.COUPONS)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Este cupón no está disponible");
        }
        Coupon coupon = couponRepository
                .findByCodeIgnoreCaseAndCompanyForUpdate(couponCode.trim(), company)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Cupón no encontrado"));

        LocalDateTime now = LocalDateTime.now();
        int usageCount = coupon.getUsageCount() == null ? 0 : coupon.getUsageCount();
        Double discountValue = coupon.getDiscountValue();

        if (!coupon.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El cupón no está activo");
        }
        if (coupon.getExpiresAt() != null && !coupon.getExpiresAt().isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El cupón ha vencido");
        }
        if (coupon.getUsageLimit() != null && usageCount >= coupon.getUsageLimit()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El cupón alcanzó su límite de usos");
        }
        if (coupon.getMinOrderAmount() != null && baseTotal < coupon.getMinOrderAmount()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El pedido no alcanza el monto mínimo");
        }
        if (discountValue == null || !Double.isFinite(discountValue) || discountValue <= 0
                || (coupon.getDiscountType() == Coupon.DiscountType.PERCENTAGE && discountValue > 100)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El cupón tiene un descuento inválido");
        }

        double discount = coupon.getDiscountType() == Coupon.DiscountType.PERCENTAGE
                ? baseTotal * (discountValue / 100)
                : Math.min(discountValue, baseTotal);
        order.setCouponCode(coupon.getCode());
        order.setDiscountAmount(discount);
        order.setTotal(Math.max(baseTotal - discount, 0));
        coupon.setUsageCount(usageCount + 1);
    }

    // ─── Estados ──────────────────────────────────────────────────────────────

    /**
     * Mueve el pedido a otra etapa. Cancelar exige motivo, devuelve el stock y
     * rechaza los cobros que seguían pendientes; los ya aprobados quedan para
     * reembolsar desde Pagos.
     */
    @Transactional
    public OrderDetail changeStatus(Member member, Long orderId, StatusRequest request) {
        OrderStatus target = parseStatus(request.status());
        if (target == OrderStatus.COMPLETED) target = OrderStatus.DELIVERED;
        if (!member.can(target == OrderStatus.CANCELLED ? Permission.ORDER_CANCEL : Permission.ORDER_UPDATE)) {
            throw AccessService.missing(target == OrderStatus.CANCELLED ? Permission.ORDER_CANCEL : Permission.ORDER_UPDATE);
        }

        Order order = orderRepository.findByIdAndCompanyIdForUpdate(orderId, member.companyId())
                .orElseThrow(() -> new NotFoundException("Pedido no encontrado"));
        OrderStatus current = order.getStatus() == null ? OrderStatus.PENDING : order.getStatus();
        if (current == target) {
            throw new BusinessException("El pedido ya está en ese estado.");
        }
        if (!current.canMoveTo(target)) {
            throw new BusinessException(current.isFinal()
                    ? "El pedido está " + label(current) + " y ya no puede cambiar de estado."
                    : "No se puede pasar un pedido de " + label(current) + " a " + label(target) + ".");
        }

        String note = trim(request.note(), 300);
        if (target == OrderStatus.CANCELLED) {
            if (note == null) throw new BusinessException("Indicá el motivo de la cancelación.");
            String reference = "Pedido #" + order.getId();
            for (OrderItem item : order.getItems()) {
                Prodcut product = productRepository
                        .findByIdAndCompanyForUpdate(item.getProdcut().getId(), member.company())
                        .orElse(null);
                if (product != null) {
                    inventoryService.apply(product, InventoryMovement.Type.CANCELLATION, item.getQuantity(),
                            note, reference, order.getId(), member.displayName());
                }
            }
            order.setCancelReason(note);
            for (OrderPayment payment : paymentRepository.findByOrderIdOrderByCreatedAtAscIdAsc(order.getId())) {
                if (payment.getStatus() == OrderPayment.Status.PENDING) {
                    payment.setStatus(OrderPayment.Status.REJECTED);
                    payment.setNote(trim("Pedido cancelado: " + note, 300));
                }
            }
        }

        order.setStatus(target);
        orderRepository.save(order);
        statusChangeRepository.save(new OrderStatusChange(order.getId(), member.companyId(), current, target,
                note, member.displayName()));
        return detail(member.companyId(), order.getId());
    }

    // ─── Consultas ────────────────────────────────────────────────────────────

    public PageResponse<OrderRow> search(Long companyId, String status, String query, LocalDate from, LocalDate to,
                                         String paymentMethod, int page, int size) {
        ZoneId zone = clock.zone(companyId);
        Specification<Order> spec = (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("company").get("id"), companyId));
            Set<OrderStatus> statuses = statusFilter(status);
            if (statuses != null) predicates.add(root.get("status").in(statuses));
            if (query != null && !query.isBlank()) {
                String q = query.strip();
                String digits = q.replace("#", "");
                List<Predicate> any = new ArrayList<>();
                any.add(cb.like(cb.lower(root.get("customerName")), "%" + q.toLowerCase(Locale.ROOT) + "%"));
                if (digits.matches("\\d{1,18}")) {
                    any.add(cb.equal(root.get("id"), Long.parseLong(digits)));
                    any.add(cb.like(root.get("customerPhone"), "%" + digits + "%"));
                }
                predicates.add(cb.or(any.toArray(Predicate[]::new)));
            }
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), clock.startOf(from, zone)));
            if (to != null) predicates.add(cb.lessThan(root.get("createdAt"), clock.startOf(to.plusDays(1), zone)));
            if (paymentMethod != null && !paymentMethod.isBlank()) {
                predicates.add(cb.equal(root.get("paymentMethod"), paymentMethod));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Page<Order> result = orderRepository.findAll(spec, PageRequest.of(Math.max(page, 0),
                PageResponse.clampSize(size), Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));

        List<Long> ids = result.getContent().stream().map(Order::getId).toList();
        Map<Long, OrderItemRepository.OrderLines> lines = ids.isEmpty() ? Map.of()
                : orderItemRepository.countByOrderIds(ids).stream()
                .collect(Collectors.toMap(OrderItemRepository.OrderLines::getOrderId, Function.identity()));
        Map<Long, List<OrderPayment>> payments = ids.isEmpty() ? Map.of()
                : paymentRepository.findByOrderIdIn(ids).stream()
                .collect(Collectors.groupingBy(OrderPayment::getOrderId));

        return PageResponse.of(result, o -> {
            OrderItemRepository.OrderLines l = lines.get(o.getId());
            List<OrderPayment> orderPayments = payments.getOrDefault(o.getId(), List.of());
            double total = o.getTotal() == null ? 0 : o.getTotal();
            return new OrderRow(o.getId(), BusinessClock.withOffset(o.getCreatedAt()), o.getCustomerId(),
                    o.getCustomerName(), o.getCustomerPhone(), l == null ? 0 : l.getLines(),
                    l == null || l.getUnits() == null ? 0 : l.getUnits(), total,
                    o.getDiscountAmount() == null ? 0 : o.getDiscountAmount(), o.getCouponCode(),
                    displayStatus(o.getStatus()).name(), o.getPaymentMethod(),
                    OrderPaymentService.round(OrderPaymentService.collected(orderPayments)),
                    OrderPaymentService.paymentStatus(total, orderPayments));
        });
    }

    /** Pedidos por estado, para las pestañas. COMPLETED se cuenta como DELIVERED. */
    public Map<String, Long> statusCounts(Long companyId) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (OrderStatus s : OrderStatus.FLOW) counts.put(s.name(), 0L);
        counts.put(OrderStatus.CANCELLED.name(), 0L);
        long all = 0;
        for (OrderRepository.StatusCount row : orderRepository.countByStatus(companyId)) {
            OrderStatus s = displayStatus(row.getStatus());
            counts.merge(s.name(), row.getTotal(), Long::sum);
            all += row.getTotal();
        }
        counts.put("IN_PROGRESS", OrderStatus.IN_PROGRESS.stream().mapToLong(s -> counts.get(s.name())).sum());
        counts.put("ALL", all);
        return counts;
    }

    public OrderDetail detail(Long companyId, Long orderId) {
        Order order = orderRepository.findByIdAndCompanyId(orderId, companyId)
                .orElseThrow(() -> new NotFoundException("Pedido no encontrado"));
        List<OrderItemView> items = order.getItems() == null ? List.of() : order.getItems().stream()
                .map(i -> new OrderItemView(i.getProdcut().getId(), i.getProdcut().getName(), i.getQuantity(),
                        i.getUnitPrice() == null ? 0 : i.getUnitPrice(),
                        i.getSubTotal() == null ? 0 : i.getSubTotal()))
                .toList();
        List<StatusChangeView> history = statusChangeRepository.findByOrderIdOrderByChangedAtAscIdAsc(orderId).stream()
                .map(c -> new StatusChangeView(c.getFromStatus() == null ? null : displayStatus(c.getFromStatus()).name(),
                        displayStatus(c.getToStatus()).name(), c.getNote(), c.getChangedBy(),
                        BusinessClock.withOffset(c.getChangedAt())))
                .toList();
        List<OrderPayment> payments = paymentRepository.findByOrderIdOrderByCreatedAtAscIdAsc(orderId);
        double total = order.getTotal() == null ? 0 : order.getTotal();
        OrderStatus status = displayStatus(order.getStatus());

        return new OrderDetail(order.getId(), BusinessClock.withOffset(order.getCreatedAt()),
                BusinessClock.withOffset(order.getUpdatedAt()), status.name(),
                status.nextStatuses().stream().map(Enum::name).toList(),
                order.getCustomerId(), order.getCustomerName(), order.getCustomerPhone(), order.getCustomerAddress(),
                items.stream().mapToDouble(OrderItemView::subtotal).sum(),
                order.getDiscountAmount() == null ? 0 : order.getDiscountAmount(), order.getCouponCode(), total,
                order.getPaymentMethod(), order.getCancelReason(),
                OrderPaymentService.round(OrderPaymentService.collected(payments)),
                OrderPaymentService.paymentStatus(total, payments), items, history,
                payments.stream().map(p -> OrderPaymentService.view(p, order)).toList());
    }

    // ─── Generar URL de WhatsApp para el cliente (retornar al frontend) ───────
    public String generateWhatsAppUrl(Order order, Company company) {
        if (!com.fluxyBackend.billing.PlanCatalog.has(company, com.fluxyBackend.billing.Feature.WHATSAPP)) return null;
        if (!integrationService.whatsappEnabled(company.getId())) return null;

        String phone = company.getPhone();
        if (phone == null || phone.isBlank()) return null;

        String message = whatsAppService.buildOrderMessage(order, company);
        return whatsAppService.buildWhatsAppUrl(phone, message);
    }

    // ─── Endpoints anteriores al flujo por etapas ─────────────────────────────

    public List<Order> getOrder(String email) {
        User user = getUserByEmail(email);
        return orderRepository.findByCompany(user.getCompany());
    }

    public Order getOrderById(Long id, String email) {
        User user = getUserByEmail(email);
        return orderRepository.findByIdAndCompany(id, user.getCompany())
                .orElseThrow(() -> new NotFoundException("Pedido no encontrado"));
    }

    @Transactional
    public Order cancelOrder(Long id, Member member) {
        changeStatus(member, id, new StatusRequest(OrderStatus.CANCELLED.name(), "Cancelado desde el panel"));
        return orderRepository.findByIdAndCompanyId(id, member.companyId()).orElseThrow();
    }

    @Transactional
    public OrderRespose completeOrder(Long id, Member member) {
        changeStatus(member, id, new StatusRequest(OrderStatus.DELIVERED.name(), null));
        return mapToResponse(orderRepository.findByIdAndCompanyId(id, member.companyId()).orElseThrow());
    }

    public double getTotalSales(String email) {
        User user = getUserByEmail(email);
        return orderRepository.findByCompany(user.getCompany())
                .stream().filter(OrderService::isSale)
                .mapToDouble(Order::getTotal).sum();
    }

    private OrderRespose mapToResponse(Order order) {
        OrderRespose respose = new OrderRespose();
        respose.id = order.getId();
        respose.customerName = order.getCustomerName();
        respose.total = order.getTotal();
        respose.createdAt = order.getCreatedAt();
        respose.status = order.getStatus().toString();

        respose.items = order.getItems().stream().map(item -> {
            OrderItemResponse itemRes = new OrderItemResponse();
            itemRes.id = item.getId();
            itemRes.quantity = item.getQuantity();
            itemRes.unitPrice = item.getUnitPrice();
            itemRes.subtotal = item.getSubTotal();

            ProductResponse prod = new ProductResponse();
            prod.Id = item.getProdcut().getId();
            prod.name = item.getProdcut().getName();
            prod.price = item.getProdcut().getPrice();

            itemRes.product = prod;
            return itemRes;
        }).toList();
        return respose;
    }

    public long getOrdersCount(String email) {
        User user = getUserByEmail(email);
        return orderRepository.findByCompany(user.getCompany()).size();
    }

    public String getTopProduct(String email) {
        User user = getUserByEmail(email);
        Map<String, Integer> counter = new HashMap<>();

        for (Order order : orderRepository.findByCompany(user.getCompany())) {
            if (!isSale(order)) continue;
            for (OrderItem item : order.getItems()) {
                String name = item.getProdcut().getName();
                counter.put(name, counter.getOrDefault(name, 0) + item.getQuantity());
            }
        }
        return counter.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("Sin ventas");
    }

    public DashborardResponse getDashborard(String email) {
        User user = getUserByEmail(email);
        List<Order> allOrders = orderRepository.findByCompany(user.getCompany());

        long completedOrders = allOrders.stream().filter(OrderService::isSale).count();
        long pendingOrders   = allOrders.stream().filter(o -> o.getStatus() == OrderStatus.PENDING).count();
        double totalSales    = allOrders.stream().filter(OrderService::isSale).mapToDouble(Order::getTotal).sum();
        double avgTicket     = completedOrders > 0 ? totalSales / completedOrders : 0;

        DashborardResponse response = new DashborardResponse();
        response.totalSales      = totalSales;
        response.ordersCount     = allOrders.size();
        response.completedOrders = completedOrders;
        response.pendingOrders   = pendingOrders;
        response.averageTicket   = avgTicket;
        response.topProduct      = getTopProduct(email);
        return response;
    }

    public List<SalesPerDayResponse> getSalesPerDay(String email) {
        User user = getUserByEmail(email);
        Map<String, Double> map = new HashMap<>();

        for (Order order : orderRepository.findByCompany(user.getCompany())) {
            if (!isSale(order)) continue;
            String date = order.getCreatedAt().toLocalDate().toString();
            map.put(date, map.getOrDefault(date, 0.0) + order.getTotal());
        }

        return map.entrySet().stream().map(entry -> {
            SalesPerDayResponse response = new SalesPerDayResponse();
            response.date = entry.getKey();
            response.total = entry.getValue();
            return response;
        }).toList();
    }

    public List<TopProductResponse> getTopProducts(String email, String period) {
        User user = getUserByEmail(email);
        LocalDateTime startDate;

        if ("today".equals(period)) {
            startDate = LocalDateTime.now().toLocalDate().atStartOfDay();
        } else {
            startDate = LocalDateTime.now().minusMonths(1);
        }

        Map<String, int[]> counter = new HashMap<>();

        for (Order order : orderRepository.findByCompany(user.getCompany())) {
            if (!isSale(order)) continue;
            if (order.getCreatedAt().isBefore(startDate)) continue;

            for (OrderItem item : order.getItems()) {
                String name = item.getProdcut().getName();
                counter.computeIfAbsent(name, k -> new int[]{0, 0});
                counter.get(name)[0] += item.getQuantity();
                counter.get(name)[1] += (int)(item.getSubTotal() * 100);
            }
        }

        return counter.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]))
                .limit(5)
                .map(entry -> {
                    TopProductResponse r = new TopProductResponse();
                    r.productName = entry.getKey();
                    r.quantitySold = entry.getValue()[0];
                    r.totalRevenue = entry.getValue()[1] / 100.0;
                    return r;
                })
                .toList();
    }

    // ─── Apoyo ────────────────────────────────────────────────────────────────

    static boolean isSale(Order order) {
        return order.getStatus() != null && order.getStatus().isSale();
    }

    /** COMPLETED es el nombre viejo de DELIVERED; nunca se muestra. */
    static OrderStatus displayStatus(OrderStatus status) {
        if (status == null) return OrderStatus.PENDING;
        return status == OrderStatus.COMPLETED ? OrderStatus.DELIVERED : status;
    }

    private static Set<OrderStatus> statusFilter(String status) {
        if (status == null || status.isBlank() || "ALL".equals(status)) return null;
        if ("IN_PROGRESS".equals(status)) return OrderStatus.IN_PROGRESS;
        OrderStatus s = parseStatus(status);
        if (s == OrderStatus.DELIVERED || s == OrderStatus.COMPLETED) {
            return EnumSet.of(OrderStatus.DELIVERED, OrderStatus.COMPLETED);
        }
        return EnumSet.of(s);
    }

    private static OrderStatus parseStatus(String value) {
        try {
            return OrderStatus.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException("Estado de pedido inválido: " + value);
        }
    }

    private static String label(OrderStatus status) {
        return switch (displayStatus(status)) {
            case PENDING -> "pendiente";
            case CONFIRMED -> "confirmado";
            case PREPARING -> "en preparación";
            case READY -> "listo";
            case SHIPPED -> "enviado";
            case DELIVERED, COMPLETED -> "entregado";
            case CANCELLED -> "cancelado";
        };
    }

    private static String trim(String value, int max) {
        if (value == null) return null;
        String t = value.strip();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }
}
