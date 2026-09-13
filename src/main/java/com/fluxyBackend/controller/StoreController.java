package com.fluxyBackend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.fluxyBackend.exception.NotFoundException;

import com.fluxyBackend.DTOs.CreateOrderRequest;
import com.fluxyBackend.DTOs.PublicStoreResponse;
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

@Tag(name = "Tienda pública", description = "Catálogo y pedidos de clientes sin autenticación.")
@RestController
@RequestMapping("/store")
@RequiredArgsConstructor
public class StoreController {

    private final ProductRepository productRepository;
    private final CompanyRepository companyRepository;
    private final OrderService orderService;

    // ─── Catálogo público de productos ───────────────────────────────────────
    @Operation(summary = "Listar productos por empresa",
            description = "Consulta el catálogo público por identificador de empresa.")
    @GetMapping("/{companyId}/products")
    public List<Prodcut> getProducts(@PathVariable Long companyId) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Empresa no encontrada"));
        return productRepository.findByCompany(company);
    }

    // ─── Crear orden como cliente (por ID) ───────────────────────────────────
    @Operation(summary = "Crear un pedido público por empresa",
            description = "Devuelve order, orderId y total. Puede incluir whatsappUrl para planes PRO o BUSINESS.")
    @PostMapping("/{companyId}/order")
    public Map<String, Object> createOrder(@PathVariable Long companyId,
                                           @Valid @RequestBody CreateOrderRequest request) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Empresa no encontrada"));

        Order order = orderService.createOrderAsClient(request, company);

        Map<String, Object> response = new HashMap<>();
        response.put("order", order);
        response.put("orderId", order.getId());
        response.put("total", order.getTotal());

        // WhatsApp URL solo si el plan es PRO o BUSINESS
        String whatsappUrl = orderService.generateWhatsAppUrl(order, company);
        if (whatsappUrl != null) {
            response.put("whatsappUrl", whatsappUrl);
        }

        return response;
    }

    // ─── Info de empresa ──────────────────────────────────────────────────────
    @Operation(summary = "Consultar una tienda por empresa",
            description = "Devuelve los datos públicos de la tienda.")
    @GetMapping("/{companyId}/info")
    public PublicStoreResponse getCompanyInfo(@PathVariable Long companyId) {
        return PublicStoreResponse.from(companyRepository.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Empresa no encontrada")));
    }

    // ─── Por slug ─────────────────────────────────────────────────────────────
    @Operation(summary = "Consultar una tienda por slug",
            description = "Devuelve los datos públicos usando el slug de la tienda.")
    @GetMapping("/slug/{slug}/info")
    public PublicStoreResponse getBySlug(@PathVariable String slug) {
        return PublicStoreResponse.from(companyRepository.findBySlug(slug)
                .orElseThrow(() -> new NotFoundException("Tienda no encontrada")));
    }

    @Operation(summary = "Listar productos por slug",
            description = "Consulta el catálogo público usando el slug de la tienda.")
    @GetMapping("/slug/{slug}/products")
    public List<Prodcut> getProductsBySlug(@PathVariable String slug) {
        Company company = companyRepository.findBySlug(slug)
                .orElseThrow(() -> new NotFoundException("Tienda no encontrada"));
        return productRepository.findByCompany(company);
    }

    // ─── Crear orden por slug ─────────────────────────────────────────────────
    @Operation(summary = "Crear un pedido público por slug",
            description = "Devuelve order, orderId y total. Puede incluir whatsappUrl para planes PRO o BUSINESS.")
    @PostMapping("/slug/{slug}/order")
    public Map<String, Object> createOrderBySlug(@PathVariable String slug,
                                                 @Valid @RequestBody CreateOrderRequest request) {
        Company company = companyRepository.findBySlug(slug)
                .orElseThrow(() -> new NotFoundException("Tienda no encontrada"));

        Order order = orderService.createOrderAsClient(request, company);

        Map<String, Object> response = new HashMap<>();
        response.put("order", order);
        response.put("orderId", order.getId());
        response.put("total", order.getTotal());

        String whatsappUrl = orderService.generateWhatsAppUrl(order, company);
        if (whatsappUrl != null) {
            response.put("whatsappUrl", whatsappUrl);
        }

        return response;
    }
}
