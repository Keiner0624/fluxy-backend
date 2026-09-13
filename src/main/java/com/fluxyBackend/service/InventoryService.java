package com.fluxyBackend.service;

import com.fluxyBackend.DTOs.PageResponse;
import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.InventoryMovement;
import com.fluxyBackend.entity.InventoryMovement.Type;
import com.fluxyBackend.entity.Prodcut;
import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.exception.NotFoundException;
import com.fluxyBackend.repository.InventoryMovementRepository;
import com.fluxyBackend.repository.ProductRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Stock y su trazabilidad. Todo cambio de stock pasa por apply(), que deja el
 * movimiento con el antes y el después.
 */
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final ProductRepository productRepository;
    private final InventoryMovementRepository movementRepository;

    public record MovementView(Long id, Long productId, String productName, String type, int quantity,
                               int stockBefore, int stockAfter, String reason, String reference, Long orderId,
                               String createdBy, OffsetDateTime createdAt) {}

    public record StockItem(Long id, String name, String sku, String imageUrl, String category, String status,
                            int stock, int minStock, Double cost, double price, String stockStatus) {}

    public record Summary(long products, long lowStock, long outOfStock, double inventoryValue,
                          long withoutCost) {}

    public record AdjustRequest(String type, Integer quantity, String reason) {}

    // ─── Registro ─────────────────────────────────────────────────────────────

    /**
     * Cambia el stock de un producto ya bloqueado y registra el movimiento.
     * Quien llama debe haberlo obtenido con findByIdAndCompanyForUpdate.
     */
    @Transactional
    public InventoryMovement apply(Prodcut product, Type type, int delta, String reason, String reference,
                                   Long orderId, String createdBy) {
        int before = product.getStock();
        int after = before + delta;
        if (after < 0) {
            throw new BusinessException("Stock insuficiente de " + product.getName()
                    + ": hay " + before + " y se necesitan " + (-delta) + ".");
        }
        product.setStock(after);

        InventoryMovement movement = new InventoryMovement();
        movement.setCompanyId(product.getCompany().getId());
        movement.setProductId(product.getId());
        movement.setProductName(product.getName());
        movement.setType(type);
        movement.setQuantity(delta);
        movement.setStockBefore(before);
        movement.setStockAfter(after);
        movement.setReason(trim(reason, 300));
        movement.setReference(trim(reference, 80));
        movement.setOrderId(orderId);
        movement.setCreatedBy(trim(createdBy, 150));
        return movementRepository.save(movement);
    }

    /** Stock inicial de un producto nuevo, o el que tenía al activar el inventario. */
    @Transactional
    public void recordInitial(Prodcut product, String createdBy, String reason) {
        InventoryMovement movement = new InventoryMovement();
        movement.setCompanyId(product.getCompany().getId());
        movement.setProductId(product.getId());
        movement.setProductName(product.getName());
        movement.setType(Type.INITIAL);
        movement.setQuantity(product.getStock());
        movement.setStockBefore(0);
        movement.setStockAfter(product.getStock());
        movement.setReason(reason);
        movement.setCreatedBy(trim(createdBy, 150));
        movementRepository.save(movement);
    }

    /**
     * Entrada, salida o ajuste manual. En ENTRY y EXIT la cantidad es lo que
     * entra o sale; en ADJUSTMENT es el stock contado, y se registra la diferencia.
     */
    @Transactional
    public MovementView adjust(Company company, Long productId, AdjustRequest request, String createdBy) {
        Type type = parseManualType(request.type());
        Integer quantity = request.quantity();
        if (quantity == null || quantity < 0 || quantity > 1_000_000) {
            throw new BusinessException("Indicá una cantidad entre 0 y 1.000.000.");
        }
        if (type != Type.ADJUSTMENT && quantity == 0) {
            throw new BusinessException("La cantidad tiene que ser mayor que cero.");
        }
        String reason = trim(request.reason(), 300);
        if (type != Type.ENTRY && reason == null) {
            throw new BusinessException("Indicá el motivo: queda en el historial del producto.");
        }

        Prodcut product = productRepository.findByIdAndCompanyForUpdate(productId, company)
                .orElseThrow(() -> new NotFoundException("Producto no encontrado"));
        int delta = switch (type) {
            case ENTRY -> quantity;
            case EXIT -> -quantity;
            default -> quantity - product.getStock();
        };
        if (type == Type.ADJUSTMENT && delta == 0) {
            throw new BusinessException("El stock contado es igual al registrado: no hay nada que ajustar.");
        }
        return view(apply(product, type, delta, reason, null, null, createdBy));
    }

    @Transactional
    public StockItem updateMinStock(Company company, Long productId, Integer minStock) {
        if (minStock == null || minStock < 0 || minStock > 1_000_000) {
            throw new BusinessException("El stock mínimo tiene que estar entre 0 y 1.000.000.");
        }
        Prodcut product = productRepository.findByIdAndCompany(productId, company)
                .orElseThrow(() -> new NotFoundException("Producto no encontrado"));
        product.setMinStock(minStock);
        return stockItem(productRepository.save(product));
    }

    // ─── Consultas ────────────────────────────────────────────────────────────

    public Summary summary(Long companyId) {
        ProductRepository.Stats stats = productRepository.stats(companyId, Prodcut.Status.HIDDEN);
        return new Summary(
                nz(stats.getTotal()), nz(stats.getLowStock()), nz(stats.getOutOfStock()),
                stats.getInventoryValue() == null ? 0 : Math.round(stats.getInventoryValue() * 100) / 100.0,
                nz(stats.getWithoutCost()));
    }

    public PageResponse<StockItem> stock(Long companyId, String query, String filter, int page, int size) {
        Specification<Prodcut> spec = (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("company").get("id"), companyId));
            if (query != null && !query.isBlank()) {
                String like = "%" + query.trim().toLowerCase() + "%";
                predicates.add(cb.or(cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(root.get("sku")), like)));
            }
            var minStock = cb.coalesce(root.<Integer>get("minStock"), Prodcut.DEFAULT_MIN_STOCK);
            switch (filter == null ? "all" : filter) {
                case "out" -> predicates.add(cb.le(root.get("stock"), 0));
                case "low" -> predicates.add(cb.and(cb.gt(root.get("stock"), 0), cb.le(root.get("stock"), minStock)));
                case "attention" -> predicates.add(cb.le(root.get("stock"), minStock));
                case "ok" -> predicates.add(cb.gt(root.get("stock"), minStock));
                default -> { }
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        var pageable = PageRequest.of(Math.max(page, 0), PageResponse.clampSize(size),
                Sort.by(Sort.Order.asc("stock"), Sort.Order.asc("name")));
        return PageResponse.of(productRepository.findAll(spec, pageable), this::stockItem);
    }

    public PageResponse<MovementView> movements(Long companyId, Long productId, String type, int page, int size) {
        Specification<InventoryMovement> spec = (root, cq, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("companyId"), companyId));
            if (productId != null) predicates.add(cb.equal(root.get("productId"), productId));
            if (type != null && !type.isBlank()) {
                try {
                    predicates.add(cb.equal(root.get("type"), Type.valueOf(type)));
                } catch (IllegalArgumentException ignored) {
                    predicates.add(cb.disjunction());
                }
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        var pageable = PageRequest.of(Math.max(page, 0), PageResponse.clampSize(size),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return PageResponse.of(movementRepository.findAll(spec, pageable), InventoryService::view);
    }

    public StockItem stockItem(Prodcut p) {
        String stockStatus = p.isOutOfStock() ? "OUT" : p.isLowStock() ? "LOW" : "OK";
        return new StockItem(p.getId(), p.getName(), p.getSku(), p.getImageUrl(),
                p.getCategory() == null ? null : p.getCategory().getName(), p.getStatus().name(),
                p.getStock(), p.getMinStock(), p.getCost(), p.getPrice(), stockStatus);
    }

    static MovementView view(InventoryMovement m) {
        return new MovementView(m.getId(), m.getProductId(), m.getProductName(), m.getType().name(),
                m.getQuantity(), m.getStockBefore(), m.getStockAfter(), m.getReason(), m.getReference(),
                m.getOrderId(), m.getCreatedBy(), BusinessClock.withOffset(m.getCreatedAt()));
    }

    private static Type parseManualType(String value) {
        if ("ENTRY".equals(value)) return Type.ENTRY;
        if ("EXIT".equals(value)) return Type.EXIT;
        if ("ADJUSTMENT".equals(value)) return Type.ADJUSTMENT;
        throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_MOVEMENT",
                "El tipo de movimiento tiene que ser ENTRY, EXIT o ADJUSTMENT.");
    }

    private static long nz(Long value) {
        return value == null ? 0 : value;
    }

    private static String trim(String value, int max) {
        if (value == null) return null;
        String t = value.strip();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }
}
