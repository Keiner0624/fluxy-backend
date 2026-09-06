package com.fluxyBackend.controller;

import com.fluxyBackend.exception.NotFoundException;

import com.fluxyBackend.entity.Category;
import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.repository.CategoryRepository;
import com.fluxyBackend.repository.CompanyRepository;
import com.fluxyBackend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryRepository categoryRepository;
    private final UserRepository     userRepository;
    private final CompanyRepository  companyRepository;

    private User getUser(Authentication auth) {
        return userRepository.findByEmailIgnoreCase(auth.getName())
                .orElseThrow(() -> new NotFoundException("Usuario no encontrado"));
    }

    // ─── Listar categorías del vendedor (privado) ─────────────────────────────
    @GetMapping("/categories")
    public List<Category> list(Authentication auth) {
        return categoryRepository.findByCompanyOrderByNameAsc(getUser(auth).getCompany());
    }

    // ─── Crear categoría ──────────────────────────────────────────────────────
    @PostMapping("/categories")
    public ResponseEntity<?> create(@RequestBody Map<String, String> body, Authentication auth) {
        String name = body.get("name");
        if (name == null || name.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "El nombre es obligatorio."));

        Category cat = Category.builder()
                .name(name.trim())
                .emoji(body.getOrDefault("emoji", "📦"))
                .company(getUser(auth).getCompany())
                .build();

        return ResponseEntity.ok(categoryRepository.save(cat));
    }

    // ─── Editar categoría ─────────────────────────────────────────────────────
    @PutMapping("/categories/{id}")
    public ResponseEntity<?> update(@PathVariable Long id,
                                    @RequestBody Map<String, String> body,
                                    Authentication auth) {
        User user = getUser(auth);
        Category cat = categoryRepository.findById(id)
                .filter(c -> c.getCompany().getId().equals(user.getCompany().getId()))
                .orElseThrow(() -> new NotFoundException("Categoría no encontrada"));

        if (body.containsKey("name") && !body.get("name").isBlank())
            cat.setName(body.get("name").trim());
        if (body.containsKey("emoji"))
            cat.setEmoji(body.get("emoji"));

        return ResponseEntity.ok(categoryRepository.save(cat));
    }

    // ─── Eliminar categoría ───────────────────────────────────────────────────
    @DeleteMapping("/categories/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, Authentication auth) {
        User user = getUser(auth);
        Category cat = categoryRepository.findById(id)
                .filter(c -> c.getCompany().getId().equals(user.getCompany().getId()))
                .orElseThrow(() -> new NotFoundException("Categoría no encontrada"));

        categoryRepository.delete(cat);
        return ResponseEntity.ok(Map.of("message", "Categoría eliminada."));
    }

    // ─── Público: categorías por slug (para la tienda del cliente) ────────────
    @GetMapping("/store/slug/{slug}/categories")
    public List<Category> publicBySlug(@PathVariable String slug) {
        Company company = companyRepository.findBySlug(slug)
                .orElseThrow(() -> new NotFoundException("Tienda no encontrada"));
        return categoryRepository.findByCompanyOrderByNameAsc(company);
    }

    // ─── Público: categorías por companyId ────────────────────────────────────
    @GetMapping("/store/{companyId}/categories")
    public List<Category> publicById(@PathVariable Long companyId) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Empresa no encontrada"));
        return categoryRepository.findByCompanyOrderByNameAsc(company);
    }
}