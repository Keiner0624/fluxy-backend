package com.fluxyBackend.service;

import com.fluxyBackend.DTOs.PageResponse;
import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.exception.NotFoundException;

import com.fluxyBackend.entity.Category;
import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Prodcut;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.exception.ProductLimitException;
import com.fluxyBackend.repository.OrderItemRepository;
import com.fluxyBackend.repository.ProductRepository;
import com.fluxyBackend.repository.CategoryRepository;
import com.fluxyBackend.repository.UserRepository;
import com.fluxyBackend.security.access.AccessService;
import com.fluxyBackend.security.access.Member;
import com.fluxyBackend.security.access.Permission;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
public class ProductService {
    private static final int MAX_BULK = 200;

    private final ProductRepository prodcutRepository;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;
    private final OrderItemRepository orderItemRepository;
    private final InventoryService inventoryService;

    public record Stats(long total, long active, long hidden, long lowStock, long outOfStock,
                        double inventoryValue, long withoutCost) {}

    public record QuickEditRequest(String name, Double price, String status, Long categoryId,
                                   Boolean clearCategory, String sku, Integer minStock, Double cost) {}

    public record BulkRequest(List<Long> ids, String action, Long categoryId) {}

    public record BulkResult(int requested, int updated, int deleted, int hiddenInstead, int notFound) {}

    private User getUserByEmail(String email) {
        return userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new NotFoundException("User not found"));
    }

    // ─── Alta y edición ───────────────────────────────────────────────────────

    @Transactional
    public Prodcut createProduct(Prodcut product, Member member) {
        Company company = member.company();
        product.setId(null);
        product.setCompany(company);
        product.setOwner(member.user());
        assignCategory(product, product.getCategory(), company);
        product.setSku(uniqueSku(company.getId(), product.getSku(), null));
        product.setCreatedAt(null);

        Company.Plan plan = com.fluxyBackend.billing.PlanCatalog.effectivePlan(company);
        int limit = com.fluxyBackend.billing.PlanCatalog.info(plan).productLimit();
        int current = prodcutRepository.countByCompany(company);

        // Con un plan vencido o inferior no se borra nada: solo no se pueden crear más.
        if (limit != com.fluxyBackend.billing.PlanCatalog.UNLIMITED && current >= limit) {
            throw new ProductLimitException(limit, current, plan.name());
        }
        Prodcut saved = prodcutRepository.save(product);
        inventoryService.recordInitial(saved, member.displayName(), "Stock inicial al crear el producto");
        return saved;
    }

    public List<Prodcut> getAll(String email) {
        User user = getUserByEmail(email);
        return prodcutRepository.findByCompany(user.getCompany());
    }

    /**
     * Edición completa desde el formulario. El stock no se toca: el formulario
     * pudo abrirse antes de una venta y guardaría un valor viejo, deshaciéndola.
     * El stock cambia solo por pedidos o por movimientos en Inventario.
     */
    @Transactional
    public Prodcut update(Long id, Prodcut update, Member member) {
        Company company = member.company();
        Prodcut prodcut = prodcutRepository.findByIdAndCompany(id, company)
                .orElseThrow(() -> new NotFoundException("Producto no encontrado"));

        prodcut.setName(update.getName());
        prodcut.setPrice(update.getPrice());
        if (update.getDescription() != null)
            prodcut.setDescription(update.getDescription());
        if (update.getImageUrl() != null)
            prodcut.setImageUrl(update.getImageUrl());
        if (update.getImages() != null)
            prodcut.setImages(update.getImages());
        assignCategory(prodcut, update.getCategory(), company);
        prodcut.setSku(uniqueSku(company.getId(), update.getSku(), prodcut.getId()));
        prodcut.setCost(update.getCost());
        if (update.getStatus() != null) prodcut.setStatus(update.getStatus());
        prodcut.setMinStock(update.getMinStock());
        return prodcutRepository.save(prodcut);
    }

    /** Edición rápida desde la tabla: solo los campos enviados. El stock se cambia en Inventario. */
    @Transactional
    public Prodcut quickEdit(Long id, QuickEditRequest request, Member member) {
        Company company = member.company();
        Prodcut product = prodcutRepository.findByIdAndCompany(id, company)
                .orElseThrow(() -> new NotFoundException("Producto no encontrado"));
        if (request.name() != null) {
            String name = request.name().strip();
            if (name.isEmpty() || name.length() > 200) throw new BusinessException("El nombre es obligatorio (máximo 200 caracteres).");
            product.setName(name);
        }
        if (request.price() != null) {
            if (!Double.isFinite(request.price()) || request.price() < 0) throw new BusinessException("El precio no puede ser negativo.");
            product.setPrice(request.price());
        }
        if (request.status() != null) product.setStatus(parseStatus(request.status()));
        if (Boolean.TRUE.equals(request.clearCategory())) {
            product.setCategory(null);
        } else if (request.categoryId() != null) {
            product.setCategory(findCategory(request.categoryId(), company));
        }
        if (request.sku() != null) product.setSku(uniqueSku(company.getId(), request.sku(), product.getId()));
        if (request.minStock() != null) {
            if (request.minStock() < 0) throw new BusinessException("El stock mínimo no puede ser negativo.");
            product.setMinStock(request.minStock());
        }
        if (request.cost() != null) {
            if (request.cost() < 0) throw new BusinessException("El costo no puede ser negativo.");
            product.setCost(request.cost());
        }
        return prodcutRepository.save(product);
    }

    /**
     * Un producto que aparece en pedidos no se borra: el pedido perdería su
     * detalle. Para sacarlo de la tienda se oculta.
     */
    @Transactional
    public void delete(Long id, Member member) {
        Prodcut prodcut = prodcutRepository.findByIdAndCompany(id, member.company())
                .orElseThrow(() -> new NotFoundException("Producto no encontrado"));
        if (!orderItemRepository.findReferencedProductIds(List.of(id)).isEmpty()) {
            throw BusinessException.conflict("PRODUCT_HAS_ORDERS",
                    "“" + prodcut.getName() + "” tiene pedidos registrados y no se puede eliminar. "
                            + "Ocultalo para sacarlo de la tienda sin perder el historial.");
        }
        prodcutRepository.delete(prodcut);
    }

    @Transactional
    public BulkResult bulk(BulkRequest request, Member member, AccessService access) {
        List<Long> ids = request.ids() == null ? List.of() : request.ids().stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) throw new BusinessException("Seleccioná al menos un producto.");
        if (ids.size() > MAX_BULK) throw new BusinessException("Podés modificar hasta " + MAX_BULK + " productos a la vez.");
        String action = request.action() == null ? "" : request.action();

        Company company = member.company();
        List<Prodcut> products = prodcutRepository.findByCompanyIdAndIdIn(company.getId(), ids);
        int notFound = ids.size() - products.size();

        switch (action) {
            case "ACTIVATE", "HIDE" -> {
                access.require(Permission.PRODUCT_UPDATE);
                Prodcut.Status status = "HIDE".equals(action) ? Prodcut.Status.HIDDEN : Prodcut.Status.ACTIVE;
                products.forEach(p -> p.setStatus(status));
                prodcutRepository.saveAll(products);
                return new BulkResult(ids.size(), products.size(), 0, 0, notFound);
            }
            case "SET_CATEGORY" -> {
                access.require(Permission.PRODUCT_UPDATE);
                Category category = request.categoryId() == null ? null : findCategory(request.categoryId(), company);
                products.forEach(p -> p.setCategory(category));
                prodcutRepository.saveAll(products);
                return new BulkResult(ids.size(), products.size(), 0, 0, notFound);
            }
            case "DELETE" -> {
                access.require(Permission.PRODUCT_DELETE);
                Set<Long> referenced = new HashSet<>(orderItemRepository.findReferencedProductIds(
                        products.stream().map(Prodcut::getId).toList()));
                List<Prodcut> deletable = products.stream().filter(p -> !referenced.contains(p.getId())).toList();
                List<Prodcut> toHide = products.stream().filter(p -> referenced.contains(p.getId())).toList();
                toHide.forEach(p -> p.setStatus(Prodcut.Status.HIDDEN));
                prodcutRepository.saveAll(toHide);
                prodcutRepository.deleteAll(deletable);
                return new BulkResult(ids.size(), 0, deletable.size(), toHide.size(), notFound);
            }
            default -> throw new BusinessException("Acción no válida. Usá ACTIVATE, HIDE, SET_CATEGORY o DELETE.");
        }
    }

    // ─── Consultas ────────────────────────────────────────────────────────────

    public PageResponse<Prodcut> search(Long companyId, String query, String category, String status, String stock,
                                        Double minPrice, Double maxPrice, String sort, String direction,
                                        int page, int size) {
        Specification<Prodcut> spec = (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("company").get("id"), companyId));
            if (query != null && !query.isBlank()) {
                String like = "%" + query.strip().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(root.get("sku")), like)));
            }
            if ("none".equals(category)) {
                predicates.add(cb.isNull(root.get("category")));
            } else if (category != null && category.matches("\\d{1,18}")) {
                predicates.add(cb.equal(root.get("category").get("id"), Long.parseLong(category)));
            }
            if ("HIDDEN".equals(status)) {
                predicates.add(cb.equal(root.get("status"), Prodcut.Status.HIDDEN));
            } else if ("ACTIVE".equals(status)) {
                predicates.add(cb.or(cb.isNull(root.get("status")), cb.equal(root.get("status"), Prodcut.Status.ACTIVE)));
            }
            var minStock = cb.coalesce(root.<Integer>get("minStock"), Prodcut.DEFAULT_MIN_STOCK);
            switch (stock == null ? "" : stock) {
                case "out" -> predicates.add(cb.le(root.get("stock"), 0));
                case "low" -> predicates.add(cb.and(cb.gt(root.get("stock"), 0), cb.le(root.get("stock"), minStock)));
                case "in" -> predicates.add(cb.gt(root.get("stock"), 0));
                default -> { }
            }
            if (minPrice != null) predicates.add(cb.ge(root.get("price"), minPrice));
            if (maxPrice != null) predicates.add(cb.le(root.get("price"), maxPrice));
            return cb.and(predicates.toArray(Predicate[]::new));
        };

        String property = switch (sort == null ? "" : sort) {
            case "price", "stock", "name" -> sort;
            default -> "createdAt";
        };
        Sort.Direction dir = "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;
        if (sort == null || sort.isBlank()) dir = Sort.Direction.DESC;
        var pageable = PageRequest.of(Math.max(page, 0), PageResponse.clampSize(size),
                Sort.by(new Sort.Order(dir, property), Sort.Order.desc("id")));
        return PageResponse.of(prodcutRepository.findAll(spec, pageable), p -> p);
    }

    public Stats stats(Long companyId) {
        ProductRepository.Stats s = prodcutRepository.stats(companyId, Prodcut.Status.HIDDEN);
        long total = nz(s.getTotal());
        long hidden = nz(s.getHidden());
        return new Stats(total, total - hidden, hidden, nz(s.getLowStock()), nz(s.getOutOfStock()),
                s.getInventoryValue() == null ? 0 : Math.round(s.getInventoryValue() * 100) / 100.0,
                nz(s.getWithoutCost()));
    }

    public int conuntProducts(String email) {
        User user = getUserByEmail(email);
        return prodcutRepository.countByCompany(user.getCompany());
    }

    // ─── Apoyo ────────────────────────────────────────────────────────────────

    private void assignCategory(Prodcut product, Category requested, Company company) {
        if (requested == null || requested.getId() == null) {
            product.setCategory(null);
            return;
        }
        product.setCategory(findCategory(requested.getId(), company));
    }

    private Category findCategory(Long categoryId, Company company) {
        return categoryRepository.findByIdAndCompany(categoryId, company)
                .orElseThrow(() -> new NotFoundException("Categoría no encontrada"));
    }

    /** SKU sin espacios sobrantes y único dentro de la empresa; vacío queda sin SKU. */
    private String uniqueSku(Long companyId, String sku, Long productId) {
        if (sku == null || sku.isBlank()) return null;
        String normalized = sku.strip().toUpperCase(Locale.ROOT);
        if (normalized.length() > 64) throw new BusinessException("El SKU puede tener hasta 64 caracteres.");
        boolean taken = productId == null
                ? prodcutRepository.existsByCompanyIdAndSkuIgnoreCase(companyId, normalized)
                : prodcutRepository.existsByCompanyIdAndSkuIgnoreCaseAndIdNot(companyId, normalized, productId);
        if (taken) throw BusinessException.conflict("SKU_TAKEN", "Ya tenés otro producto con el SKU " + normalized + ".");
        return normalized;
    }

    private static Prodcut.Status parseStatus(String value) {
        try {
            return Prodcut.Status.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Estado de producto inválido: " + value);
        }
    }

    private static long nz(Long value) {
        return value == null ? 0 : value;
    }
}
