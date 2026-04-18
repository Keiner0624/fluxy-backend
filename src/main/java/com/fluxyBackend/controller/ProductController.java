package com.fluxyBackend.controller;

import com.fluxyBackend.entity.Prodcut;
import com.fluxyBackend.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductController {
    private final ProductService prodcutService;

    @PostMapping
    public Prodcut create(@RequestBody Prodcut prodcut, Authentication authentication) {
        return prodcutService.createProduct(prodcut, authentication.getName());
    }
    @GetMapping
    public List<Prodcut> getAll(Authentication authentication) {
        return prodcutService.getAll(authentication.getName());
    }

    @PutMapping("/{id}")
    public Prodcut update(@PathVariable Long id, @RequestBody Prodcut prodcut, Authentication authentication) {
        return prodcutService.update(id, prodcut, authentication.getName());
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id, Authentication authentication) {
        prodcutService.delete(id, authentication.getName());
    }
}
