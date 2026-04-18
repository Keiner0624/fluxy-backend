package com.fluxyBackend.service;

import com.fluxyBackend.DTOs.CreateOrderRequest;
import com.fluxyBackend.DTOs.DashborardResponse;
import com.fluxyBackend.DTOs.OrderItemsRequest;
import com.fluxyBackend.DTOs.SalesPerDayResponse;
import com.fluxyBackend.entity.*;
import com.fluxyBackend.repository.OrderRepository;
import com.fluxyBackend.repository.ProductRepository;
import com.fluxyBackend.repository.UserRepository;
import com.fluxyBackend.response.OrderItemResponse;
import com.fluxyBackend.response.OrderRespose;
import com.fluxyBackend.response.ProductResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.awt.*;
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

    private User getUserByEmail(String email) {
        return userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
    }

    public Order createOrder(CreateOrderRequest request, String email) {
        User user = getUserByEmail(email);

        Order order = new Order();
        order.setCustomerName(request.customerName);
        order.setCreatedAt(LocalDateTime.now());
        order.setStatus(OrderStatus.PENDING);
        order.setOwner(user);
        order.setCompany(user.getCompany());

        List<OrderItem> orderItems = new ArrayList<>();
        double toal = 0.0;

        for (OrderItemsRequest itemsRequest : request.items) {
            System.out.println("====== DEBUG ORDER ======");
            System.out.println("USER EMAIL: " + email);
            System.out.println("PRODUCT ID: " + itemsRequest.productId);

// Verifica si el producto existe sin filtro de usuario
            Prodcut test = productRepository.findById(itemsRequest.productId).orElse(null);

            System.out.println("PRODUCT EXISTS: " + (test != null));

            if (test != null) {
                System.out.println("PRODUCT OWNER ID: " +
                        (test.getOwner() != null ? test.getOwner().getId() : "NULL"));
            }
            System.out.println("=========================");
            Prodcut prodcut = productRepository.findByIdAndCompany(itemsRequest.productId, user.getCompany())
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
            toal += subTotal;
        }
        order.setItems(orderItems);
        order.setTotal(toal);
        return orderRepository.save(order);
    }
    public List<Order> getOrder(String email) {
        User  user = getUserByEmail(email);
        return orderRepository.findByCompany(user.getCompany());
    }

    public Order getOrderById(Long id, String email) {
        User  user = getUserByEmail(email);
        return (Order) orderRepository.findByIdAndCompany(id, user.getCompany()).orElseThrow(() -> new RuntimeException("Pedido no encontrado"));
    }
    public Order cancelOrder(Long id, String email) {
        User user = getUserByEmail(email);
        Order order = (Order) orderRepository.findByIdAndCompany(id, user.getCompany()).orElseThrow(()  -> new RuntimeException("Pedido no encontrado"));

        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new RuntimeException("El pedido ha sido cancelado.");
        }

        //devolver stock
        for (OrderItem item : order.getItems()) {
            Prodcut prodcut = item.getProdcut();
            prodcut.setStock(prodcut.getStock() + item.getQuantity());
        }
        order.setStatus(OrderStatus.CANCELLED);
        return orderRepository.save(order);
    }

    public double getTotalSales(String email){
        User user = getUserByEmail(email);
        return orderRepository.findByCompany(user.getCompany())
                .stream().filter(o -> o.getStatus() == OrderStatus.COMPLETED)
                .mapToDouble(Order::getTotal).sum();
    }

    public OrderRespose completeOrder(Long id, String email) {
        User user = getUserByEmail(email);
        Order order = (Order) orderRepository.findByIdAndCompany(id, user.getCompany())
                .orElseThrow(() -> new RuntimeException("Pedido no encontrado"));

        if (order.getStatus() == OrderStatus.COMPLETED) {
            throw new RuntimeException("El pedido ya está completado.");
        }
        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new RuntimeException("No puedes completar un pedido cancelado.");
        }

        order.setStatus(OrderStatus.COMPLETED);
        orderRepository.save(order); // ← ¿tiene esta línea?
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
            prod.Id =  item.getProdcut().getId();
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
        return counter.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("Sin ventas");
    }

    public DashborardResponse getDashborard(String email) {
        DashborardResponse response = new DashborardResponse();
        response.totalSales = getTotalSales(email);
        response.ordersCount = getOrdersCount(email);
        response.topProduct = getTopProduct(email);
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
    public Order createOrderAsClient(CreateOrderRequest request, Company company) {
        Order order = new Order();
        order.setCustomerName(request.customerName);
        order.setCreatedAt(LocalDateTime.now());
        order.setStatus(OrderStatus.PENDING);
        order.setCompany(company);

        List<OrderItem> orderItems = new ArrayList<>();
        double total = 0.0;

        for (OrderItemsRequest itemsRequest : request.items) {
            Prodcut prodcut = productRepository.findByIdAndCompany(
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
        return orderRepository.save(order);
    }
}

