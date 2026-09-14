package com.fluxyBackend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.fluxyBackend.exception.NotFoundException;

import com.fluxyBackend.DTOs.CreateOrderRequest;
import com.fluxyBackend.DTOs.PublicProductResponse;
import com.fluxyBackend.DTOs.PublicStoreResponse;
import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Order;
import com.fluxyBackend.entity.Prodcut;
import com.fluxyBackend.repository.CompanyRepository;
import com.fluxyBackend.repository.ProductRepository;
import com.fluxyBackend.service.CompanyLifecycleService;
import com.fluxyBackend.service.IntegrationService;
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
    private final IntegrationService integrationService;
    private final CompanyLifecycleService lifecycleService;

    // ─── Catálogo público de productos ───────────────────────────────────────
    @Operation(summary = "Listar productos por empresa",
            description = "Consulta el catálogo público por identificador de empresa. No incluye productos ocultos.")
    @GetMapping("/{companyId}/products")
    public List<PublicProductResponse> getProducts(@PathVariable Long companyId) {
        Company company = byId(companyId);
        return visibleProducts(company);
    }

    // ─── Crear orden como cliente (por ID) ───────────────────────────────────
    @Operation(summary = "Crear un pedido público por empresa",
            description = "Devuelve order, orderId y total. Puede incluir whatsappUrl para planes PRO o BUSINESS.")
    @PostMapping("/{companyId}/order")
    public Map<String, Object> createOrder(@PathVariable Long companyId,
                                           @Valid @RequestBody CreateOrderRequest request) {
        Company company = byId(companyId);
        lifecycleService.ensureStoreAcceptsOrders(company);
        Order order = orderService.createOrderAsClient(request, company);
        lifecycleService.recordActivity(company.getId());
        return orderResponse(order, company);
    }

    // ─── Info de empresa ──────────────────────────────────────────────────────
    @Operation(summary = "Consultar una tienda por empresa",
            description = "Devuelve los datos públicos de la tienda.")
    @GetMapping("/{companyId}/info")
    public PublicStoreResponse getCompanyInfo(@PathVariable Long companyId) {
        Company company = byId(companyId);
        return PublicStoreResponse.from(company, integrationService.settings(company.getId()));
    }

    // ─── Por slug ─────────────────────────────────────────────────────────────
    @Operation(summary = "Consultar una tienda por slug",
            description = "Devuelve los datos públicos usando el slug de la tienda.")
    @GetMapping("/slug/{slug}/info")
    public PublicStoreResponse getBySlug(@PathVariable String slug) {
        Company company = bySlug(slug);
        return PublicStoreResponse.from(company, integrationService.settings(company.getId()));
    }

    @Operation(summary = "Listar productos por slug",
            description = "Consulta el catálogo público usando el slug de la tienda. No incluye productos ocultos.")
    @GetMapping("/slug/{slug}/products")
    public List<PublicProductResponse> getProductsBySlug(@PathVariable String slug) {
        Company company = bySlug(slug);
        return visibleProducts(company);
    }

    // ─── Crear orden por slug ─────────────────────────────────────────────────
    @Operation(summary = "Crear un pedido público por slug",
            description = "Devuelve order, orderId y total. Puede incluir whatsappUrl para planes PRO o BUSINESS.")
    @PostMapping("/slug/{slug}/order")
    public Map<String, Object> createOrderBySlug(@PathVariable String slug,
                                                 @Valid @RequestBody CreateOrderRequest request) {
        Company company = bySlug(slug);
        lifecycleService.ensureStoreAcceptsOrders(company);
        Order order = orderService.createOrderAsClient(request, company);
        lifecycleService.recordActivity(company.getId());
        return orderResponse(order, company);
    }

    /** Tiendas archivadas o por eliminarse no se muestran (410 STORE_OFFLINE). */
    private Company byId(Long companyId) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Empresa no encontrada"));
        lifecycleService.ensureStoreOnline(company);
        return company;
    }

    private Company bySlug(String slug) {
        Company company = companyRepository.findBySlug(slug)
                .orElseThrow(() -> new NotFoundException("Tienda no encontrada"));
        lifecycleService.ensureStoreOnline(company);
        return company;
    }

    private List<PublicProductResponse> visibleProducts(Company company) {
        return productRepository.findVisibleByCompany(company, Prodcut.Status.HIDDEN).stream()
                .map(PublicProductResponse::from)
                .toList();
    }

    private Map<String, Object> orderResponse(Order order, Company company) {
        Map<String, Object> response = new HashMap<>();
        // Solo lo que el comprador necesita para su confirmación: la entidad
        // completa arrastraba la empresa y el cliente vinculado.
        response.put("order", Map.of("id", order.getId(), "total", order.getTotal(), "status", order.getStatus().name()));
        response.put("orderId", order.getId());
        response.put("total", order.getTotal());

        // WhatsApp URL solo si el plan es PRO o BUSINESS
        String whatsappUrl = orderService.generateWhatsAppUrl(order, company);
        if (whatsappUrl != null) {
            response.put("whatsappUrl", whatsappUrl);
        }
        return response;
    }
}
