package com.fluxyBackend.controller;

import com.fluxyBackend.DTOs.CreateOrderRequest;
import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Order;
import com.fluxyBackend.entity.Prodcut;
import com.fluxyBackend.repository.CompanyRepository;
import com.fluxyBackend.repository.ProductRepository;
import com.fluxyBackend.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/store")
@RequiredArgsConstructor
public class StoreController {

    private final ProductRepository productRepository;
    private final CompanyRepository companyRepository;
    private final OrderService orderService;

    // Catálogo público de productos por empresa
    @GetMapping("/{companyId}/products")
    public List<Prodcut> getProducts(@PathVariable Long companyId) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new RuntimeException("Empresa no encontrada"));
        return productRepository.findByCompany(company);
    }

    // Crear orden como cliente (sin token)
    @PostMapping("/{companyId}/order")
    public Order createOrder(@PathVariable Long companyId,
                             @RequestBody CreateOrderRequest request) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new RuntimeException("Empresa no encontrada"));
        return orderService.createOrderAsClient(request, company);
    }
    @GetMapping("/{companyId}/info")
    public Company getCompanyInfo(@PathVariable Long companyId) {
        return companyRepository.findById(companyId)
                .orElseThrow(() -> new RuntimeException("Empresa no encontrada"));
    }
}
