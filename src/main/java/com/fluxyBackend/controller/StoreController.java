package com.fluxyBackend.controller;

import com.fluxyBackend.DTOs.CreateOrderRequest;
import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Order;
import com.fluxyBackend.entity.Prodcut;
import com.fluxyBackend.repository.CompanyRepository;
import com.fluxyBackend.repository.ProductRepository;
import com.fluxyBackend.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/store")
@RequiredArgsConstructor
public class StoreController {

    private final ProductRepository productRepository;
    private final CompanyRepository companyRepository;
    private final OrderService orderService;

    // ─── Catálogo público de productos ───────────────────────────────────────
    @GetMapping("/{companyId}/products")
    public List<Prodcut> getProducts(@PathVariable Long companyId) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new RuntimeException("Empresa no encontrada"));
        return productRepository.findByCompany(company);
    }

    // ─── Crear orden como cliente (por ID) ───────────────────────────────────
    @PostMapping("/{companyId}/order")
    public Map<String, Object> createOrder(@PathVariable Long companyId,
                                           @Valid @RequestBody CreateOrderRequest request) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new RuntimeException("Empresa no encontrada"));

        Order order = orderService.createOrderAsClient(request, company);

        Map<String, Object> response = new HashMap<>();
        response.put("order", order);
        response.put("orderId", order.getId());
        response.put("total", order.getTotal());

        // ✅ WhatsApp URL solo si el plan es PRO o BUSINESS
        String whatsappUrl = orderService.generateWhatsAppUrl(order, company);
        if (whatsappUrl != null) {
            response.put("whatsappUrl", whatsappUrl);
        }

        return response;
    }

    // ─── Info de empresa ──────────────────────────────────────────────────────
    @GetMapping("/{companyId}/info")
    public Company getCompanyInfo(@PathVariable Long companyId) {
        return companyRepository.findById(companyId)
                .orElseThrow(() -> new RuntimeException("Empresa no encontrada"));
    }

    // ─── Por slug ─────────────────────────────────────────────────────────────
    @GetMapping("/slug/{slug}/info")
    public Company getBySlug(@PathVariable String slug) {
        return companyRepository.findBySlug(slug)
                .orElseThrow(() -> new RuntimeException("Tienda no encontrada"));
    }

    @GetMapping("/slug/{slug}/products")
    public List<Prodcut> getProductsBySlug(@PathVariable String slug) {
        Company company = companyRepository.findBySlug(slug)
                .orElseThrow(() -> new RuntimeException("Tienda no encontrada"));
        return productRepository.findByCompany(company);
    }

    // ─── Crear orden por slug ─────────────────────────────────────────────────
    @PostMapping("/slug/{slug}/order")
    public Map<String, Object> createOrderBySlug(@PathVariable String slug,
                                                 @Valid @RequestBody CreateOrderRequest request) {
        Company company = companyRepository.findBySlug(slug)
                .orElseThrow(() -> new RuntimeException("Tienda no encontrada"));

        Order order = orderService.createOrderAsClient(request, company);

        Map<String, Object> response = new HashMap<>();
        response.put("order", order);
        response.put("orderId", order.getId());
        response.put("total", order.getTotal());

        // ✅ WhatsApp URL solo si el plan es PRO o BUSINESS
        String whatsappUrl = orderService.generateWhatsAppUrl(order, company);
        if (whatsappUrl != null) {
            response.put("whatsappUrl", whatsappUrl);
        }

        return response;
    }
}
