package com.fluxyBackend.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;

import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.exception.NotFoundException;

import com.fluxyBackend.entity.Category;
import com.fluxyBackend.entity.Company;
import com.fluxyBackend.repository.CategoryRepository;
import com.fluxyBackend.repository.CompanyRepository;
import com.fluxyBackend.repository.ProductRepository;
import com.fluxyBackend.security.access.AccessService;
import com.fluxyBackend.security.access.Member;
import com.fluxyBackend.security.access.Permission;
import com.fluxyBackend.security.access.RequirePermission;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@Tag(name = "Categorías", description = "Organización del catálogo por categorías.")
@RestController
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryRepository categoryRepository;
    private final CompanyRepository  companyRepository;
    private final ProductRepository  productRepository;
    private final AccessService      accessService;
    private final com.fluxyBackend.service.AuditService auditService;

    public record CategoryView(Long id, String name, String emoji, String description, Integer sortOrder,
                               Boolean active, long productCount) {}

    public record PublicCategory(Long id, String name, String emoji, String description) {}

    public record CategoryRequest(String name, String emoji, String description, Boolean active) {}

    public record OrderRequest(List<Long> ids) {}

    // ─── Listar categorías del vendedor (privado) ─────────────────────────────
    @Operation(summary = "Listar mis categorías",
            description = "En el orden de la tienda, con la cantidad de productos de cada una.")
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/categories")
    @RequirePermission(Permission.PRODUCT_VIEW)
    public List<CategoryView> list() {
        Member member = accessService.current();
        Map<Long, Long> counts = productRepository.countByCategory(member.companyId()).stream()
                .collect(Collectors.toMap(ProductRepository.CategoryCount::getCategoryId,
                        ProductRepository.CategoryCount::getTotal));
        return categoryRepository.findOrdered(member.company()).stream()
                .map(c -> view(c, counts.getOrDefault(c.getId(), 0L)))
                .toList();
    }

    // ─── Crear categoría ──────────────────────────────────────────────────────
    @Operation(summary = "Crear una categoría",
            description = "name es obligatorio; emoji, description y active son opcionales. Se agrega al final.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    content = @Content(mediaType = "application/json",
                            examples = @ExampleObject(value = "{\"name\":\"Accesorios\",\"emoji\":\"📦\",\"description\":\"Complementos\"}"))))
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/categories")
    @RequirePermission(Permission.PRODUCT_CREATE)
    public CategoryView create(@RequestBody CategoryRequest body) {
        Member member = accessService.current();
        int nextPosition = categoryRepository.findOrdered(member.company()).stream()
                .map(Category::getSortOrder).filter(Objects::nonNull).max(Integer::compare).orElse(0) + 1;

        Category cat = Category.builder()
                .name(requiredName(body.name()))
                .emoji(body.emoji() == null || body.emoji().isBlank() ? "📦" : body.emoji().strip())
                .description(description(body.description()))
                .active(body.active() == null || body.active())
                .sortOrder(nextPosition)
                .company(member.company())
                .build();
        return view(categoryRepository.save(cat), 0);
    }

    // ─── Editar categoría ─────────────────────────────────────────────────────
    @Operation(summary = "Actualizar una categoría",
            description = "Actualiza solo los campos enviados.")
    @SecurityRequirement(name = "bearerAuth")
    @PutMapping("/categories/{id}")
    @RequirePermission(Permission.PRODUCT_UPDATE)
    public CategoryView update(@PathVariable Long id, @RequestBody CategoryRequest body) {
        Member member = accessService.current();
        Category cat = find(id, member.company());
        if (body.name() != null) cat.setName(requiredName(body.name()));
        if (body.emoji() != null) cat.setEmoji(body.emoji().isBlank() ? "📦" : body.emoji().strip());
        if (body.description() != null) cat.setDescription(description(body.description()));
        if (body.active() != null) cat.setActive(body.active());
        long count = productRepository.countByCategory(member.companyId()).stream()
                .filter(c -> c.getCategoryId().equals(id)).mapToLong(ProductRepository.CategoryCount::getTotal).sum();
        return view(categoryRepository.save(cat), count);
    }

    // ─── Ordenar ──────────────────────────────────────────────────────────────
    @Operation(summary = "Ordenar las categorías",
            description = "Recibe todos los ids en el orden en que deben mostrarse en la tienda.")
    @SecurityRequirement(name = "bearerAuth")
    @PutMapping("/categories/order")
    @RequirePermission(Permission.PRODUCT_UPDATE)
    @Transactional
    public List<CategoryView> reorder(@RequestBody OrderRequest body) {
        Member member = accessService.current();
        Map<Long, Category> own = categoryRepository.findOrdered(member.company()).stream()
                .collect(Collectors.toMap(Category::getId, c -> c));
        List<Long> ids = body.ids() == null ? List.of() : body.ids();
        if (!new HashSet<>(ids).equals(own.keySet()) || ids.size() != own.size()) {
            throw new BusinessException("El orden tiene que incluir cada categoría una sola vez.");
        }
        for (int i = 0; i < ids.size(); i++) own.get(ids.get(i)).setSortOrder(i + 1);
        categoryRepository.saveAll(own.values());
        return list();
    }

    // ─── Eliminar categoría ───────────────────────────────────────────────────
    @Operation(summary = "Eliminar una categoría",
            description = "Los productos de la categoría quedan sin categoría; no se borran.")
    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/categories/{id}")
    @RequirePermission(Permission.PRODUCT_DELETE)
    @Transactional
    public Map<String, Object> delete(@PathVariable Long id) {
        Member member = accessService.current();
        Category cat = find(id, member.company());
        int unassigned = productRepository.clearCategory(cat);
        categoryRepository.delete(cat);
        auditService.record(member, com.fluxyBackend.service.AuditAction.CATEGORY_DELETED, "CATEGORY", id, Map.of("name", String.valueOf(cat.getName())));
        return Map.of("message", "Categoría eliminada.", "productsUnassigned", unassigned);
    }

    // ─── Público: categorías por slug (para la tienda del cliente) ────────────
    @Operation(summary = "Listar categorías públicas por slug",
            description = "Consulta las categorías activas de una tienda sin JWT, en el orden elegido por el vendedor.")
    @GetMapping("/store/slug/{slug}/categories")
    public List<PublicCategory> publicBySlug(@PathVariable String slug) {
        Company company = companyRepository.findBySlug(slug)
                .orElseThrow(() -> new NotFoundException("Tienda no encontrada"));
        return publicCategories(company);
    }

    // ─── Público: categorías por companyId ────────────────────────────────────
    @Operation(summary = "Listar categorías públicas por empresa",
            description = "Consulta las categorías activas de una empresa sin JWT.")
    @GetMapping("/store/{companyId}/categories")
    public List<PublicCategory> publicById(@PathVariable Long companyId) {
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Empresa no encontrada"));
        return publicCategories(company);
    }

    private List<PublicCategory> publicCategories(Company company) {
        return categoryRepository.findOrdered(company).stream()
                .filter(Category::getActive)
                .map(c -> new PublicCategory(c.getId(), c.getName(), c.getEmoji(), c.getDescription()))
                .toList();
    }

    private Category find(Long id, Company company) {
        return categoryRepository.findByIdAndCompany(id, company)
                .orElseThrow(() -> new NotFoundException("Categoría no encontrada"));
    }

    private static CategoryView view(Category c, long productCount) {
        return new CategoryView(c.getId(), c.getName(), c.getEmoji(), c.getDescription(), c.getSortOrder(),
                c.getActive(), productCount);
    }

    private static String requiredName(String name) {
        String value = name == null ? "" : name.strip().replaceAll("\\s+", " ");
        if (value.isEmpty()) throw new BusinessException("El nombre es obligatorio.");
        if (value.length() > 80) throw new BusinessException("El nombre puede tener hasta 80 caracteres.");
        return value;
    }

    private static String description(String description) {
        if (description == null) return null;
        String value = description.strip();
        if (value.length() > 300) throw new BusinessException("La descripción puede tener hasta 300 caracteres.");
        return value.isEmpty() ? null : value;
    }
}
