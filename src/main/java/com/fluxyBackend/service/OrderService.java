package com.fluxyBackend.service;

import com.fluxyBackend.DTOs.*;
import com.fluxyBackend.entity.*;
import com.fluxyBackend.repository.OrderRepository;
import com.fluxyBackend.repository.ProductRepository;
import com.fluxyBackend.repository.UserRepository;
import com.fluxyBackend.repository.CouponRepository;
import com.fluxyBackend.entity.Coupon;
import com.fluxyBackend.response.OrderItemResponse;
import com.fluxyBackend.response.OrderRespose;
import com.fluxyBackend.response.ProductResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final WhatsAppService whatsAppService;
    private final CouponRepository couponRepository;
    private final ApplicationEventPublisher eventPublisher;

    private static final List<String> PRO_PLANS = List.of("PRO", "BUSINESS");

    private User getUserByEmail(String email) {
        return userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    @Transactional
    public Order createOrder(CreateOrderRequest request, String email) {
        User user = getUserByEmail(email);

        Order order = new Order();
        order.setCustomerName(request.customerName);
        order.setCreatedAt(LocalDateTime.now());
        order.setStatus(OrderStatus.PENDING);
        order.setOwner(user);
        order.setCompany(user.getCompany());

        List<OrderItem> orderItems = new ArrayList<>();
        double total = 0.0;

        for (OrderItemsRequest itemsRequest : request.items) {
            Prodcut prodcut = productRepository.findByIdAndCompanyForUpdate(
                            itemsRequest.productId, user.getCompany())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Product not found for id: " + itemsRequest.productId
                    ));

            if (prodcut.getStock() < itemsRequest.quantity) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Stock insufficient: " + prodcut.getName()
                );
            }

            double subTotal = prodcut.getPrice() * itemsRequest.quantity;
            OrderItem orderItem = new OrderItem();
            orderItem.setProdcut(prodcut);
            orderItem.setQuantity(itemsRequest.quantity);
            orderItem.setUnitPrice(prodcut.getPrice());
            orderItem.setSubTotal(subTotal);
            orderItem.setOrder(order);

            prodcut.setStock(prodcut.getStock() - itemsRequest.quantity);
            orderItems.add(orderItem);
            total += subTotal;
        }

        order.setItems(orderItems);
        order.setTotal(total);
        applyCoupon(request.couponCode, user.getCompany(), order, total);

        Order savedOrder = orderRepository.save(order);
        eventPublisher.publishEvent(new OrderCreatedEvent(savedOrder.getId(), user.getCompany().getId()));
        return savedOrder;
    }

    @Transactional
    public Order createOrderAsClient(CreateOrderRequest request, Company company) {
        Order order = new Order();
        order.setCustomerName(request.customerName);
        order.setCustomerPhone(request.customerPhone);
        order.setCustomerAddress(request.customerAddress);
        order.setCreatedAt(LocalDateTime.now());
        order.setStatus(OrderStatus.PENDING);
        order.setCompany(company);

        List<OrderItem> orderItems = new ArrayList<>();
        double total = 0.0;

        for (OrderItemsRequest itemsRequest : request.items) {
            Prodcut prodcut = productRepository.findByIdAndCompanyForUpdate(
                            itemsRequest.productId, company)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "Producto no encontrado"));

            if (prodcut.getStock() < itemsRequest.quantity) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Stock insuficiente: " + prodcut.getName());
            }

            double subTotal = prodcut.getPrice() * itemsRequest.quantity;
            OrderItem orderItem = new OrderItem();
            orderItem.setProdcut(prodcut);
            orderItem.setQuantity(itemsRequest.quantity);
            orderItem.setUnitPrice(prodcut.getPrice());
            orderItem.setSubTotal(subTotal);
            orderItem.setOrder(order);

            prodcut.setStock(prodcut.getStock() - itemsRequest.quantity);
            orderItems.add(orderItem);
            total += subTotal;
        }

        order.setItems(orderItems);
        order.setTotal(total);

        applyCoupon(request.couponCode, company, order, total);

        Order savedOrder = orderRepository.save(order);
        eventPublisher.publishEvent(new OrderCreatedEvent(savedOrder.getId(), company.getId()));
        return savedOrder;
    }

    private void applyCoupon(String couponCode, Company company, Order order, double baseTotal) {
        if (couponCode == null || couponCode.isBlank()) {
            return;
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

    // ─── Generar URL de WhatsApp para el cliente (retornar al frontend) ───────
    public String generateWhatsAppUrl(Order order, Company company) {
        String plan = company.getPlan() != null ? company.getPlan().name() : "FREE";
        if (!PRO_PLANS.contains(plan)) return null;

        String phone = company.getPhone();
        if (phone == null || phone.isBlank()) return null;

        String message = whatsAppService.buildOrderMessage(order, company);
        return whatsAppService.buildWhatsAppUrl(phone, message);
    }

    public List<Order> getOrder(String email) {
        User user = getUserByEmail(email);
        return orderRepository.findByCompany(user.getCompany());
    }

    public Order getOrderById(Long id, String email) {
        User user = getUserByEmail(email);
        return orderRepository.findByIdAndCompany(id, user.getCompany())
                .orElseThrow(() -> new RuntimeException("Pedido no encontrado"));
    }

    @Transactional
    public Order cancelOrder(Long id, String email) {
        User user = getUserByEmail(email);
        Order order = orderRepository.findByIdAndCompany(id, user.getCompany())
                .orElseThrow(() -> new RuntimeException("Pedido no encontrado"));

        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new RuntimeException("El pedido ya ha sido cancelado.");
        }
        if (order.getStatus() == OrderStatus.COMPLETED) {
            throw new RuntimeException("No puedes cancelar un pedido completado.");
        }

        for (OrderItem item : order.getItems()) {
            Prodcut prodcut = item.getProdcut();
            prodcut.setStock(prodcut.getStock() + item.getQuantity());
        }

        order.setStatus(OrderStatus.CANCELLED);
        return orderRepository.save(order);
    }

    public double getTotalSales(String email) {
        User user = getUserByEmail(email);
        return orderRepository.findByCompany(user.getCompany())
                .stream().filter(o -> o.getStatus() == OrderStatus.COMPLETED)
                .mapToDouble(Order::getTotal).sum();
    }

    @Transactional
    public OrderRespose completeOrder(Long id, String email) {
        User user = getUserByEmail(email);
        Order order = orderRepository.findByIdAndCompany(id, user.getCompany())
                .orElseThrow(() -> new RuntimeException("Pedido no encontrado"));

        if (order.getStatus() == OrderStatus.COMPLETED)
            throw new RuntimeException("El pedido ya está completado.");
        if (order.getStatus() == OrderStatus.CANCELLED)
            throw new RuntimeException("No puedes completar un pedido cancelado.");

        order.setStatus(OrderStatus.COMPLETED);
        orderRepository.save(order);
        return mapToResponse(order);
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
            if (order.getStatus() != OrderStatus.COMPLETED) continue;
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

        long completedOrders = allOrders.stream().filter(o -> o.getStatus() == OrderStatus.COMPLETED).count();
        long pendingOrders   = allOrders.stream().filter(o -> o.getStatus() == OrderStatus.PENDING).count();
        double totalSales    = allOrders.stream().filter(o -> o.getStatus() == OrderStatus.COMPLETED).mapToDouble(Order::getTotal).sum();
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
            if (order.getStatus() != OrderStatus.COMPLETED) continue;
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
            if (order.getStatus() != OrderStatus.COMPLETED) continue;
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
}
