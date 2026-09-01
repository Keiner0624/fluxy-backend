package com.fluxyBackend.controller;

import com.fluxyBackend.DTOs.CreateCouponRequest;
import com.fluxyBackend.entity.Coupon;
import com.fluxyBackend.entity.Coupon.DiscountType;
import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.repository.CompanyRepository;
import com.fluxyBackend.repository.CouponRepository;
import com.fluxyBackend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Locale;

@RestController
@RequestMapping("/coupons")
@RequiredArgsConstructor
@Validated
public class CouponController {

    private final CouponRepository  couponRepository;
    private final UserRepository    userRepository;
    private final CompanyRepository companyRepository;

    private User getUser(Authentication auth) {
        return userRepository.findByEmailIgnoreCase(auth.getName())
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));
    }

    // ─── Listar cupones del vendedor ─────────────────────────────────────────
    @GetMapping
    public List<Coupon> list(Authentication auth) {
        return couponRepository.findByCompany(getUser(auth).getCompany());
    }

    // ─── Crear cupón ─────────────────────────────────────────────────────────
    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateCouponRequest request, Authentication auth) {
        User user = getUser(auth);
        Company company = user.getCompany();

        String code = request.code.trim().toUpperCase(Locale.ROOT);

        // Verificar que no exista ya
        if (couponRepository.findByCodeIgnoreCaseAndCompany(code, company).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Ya existe un cupón con ese código."));
        }

        // Validar porcentaje
        if (request.discountType == DiscountType.PERCENTAGE && request.discountValue > 100) {
            return ResponseEntity.badRequest().body(Map.of("error", "El porcentaje debe ser entre 1 y 100."));
        }

        Coupon coupon = Coupon.builder()
                .code(code)
                .discountType(request.discountType)
                .discountValue(request.discountValue)
                .company(company)
                .active(true)
                .usageCount(0)
                .usageLimit(request.usageLimit)
                .minOrderAmount(request.minOrderAmount)
                .expiresAt(request.expiresAt)
                .build();

        return ResponseEntity.ok(couponRepository.save(coupon));
    }

    // ─── Activar / desactivar cupón ──────────────────────────────────────────
    @PutMapping("/{id}/toggle")
    public ResponseEntity<?> toggle(@PathVariable Long id, Authentication auth) {
        User user = getUser(auth);
        Coupon coupon = couponRepository.findById(id)
                .filter(c -> c.getCompany().getId().equals(user.getCompany().getId()))
                .orElseThrow(() -> new RuntimeException("Cupón no encontrado"));

        coupon.setActive(!coupon.isActive());
        return ResponseEntity.ok(couponRepository.save(coupon));
    }

    // ─── Eliminar cupón ───────────────────────────────────────────────────────
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, Authentication auth) {
        User user = getUser(auth);
        Coupon coupon = couponRepository.findById(id)
                .filter(c -> c.getCompany().getId().equals(user.getCompany().getId()))
                .orElseThrow(() -> new RuntimeException("Cupón no encontrado"));

        couponRepository.delete(coupon);
        return ResponseEntity.ok(Map.of("message", "Cupón eliminado."));
    }

    // ─── Validar cupón (público — lo llama el cliente al checkout) ───────────
    // GET /coupons/validate?code=PROMO10&companyId=1&orderTotal=50
    @GetMapping("/validate")
    public ResponseEntity<?> validate(
            @RequestParam String code,
            @RequestParam Long companyId,
            @RequestParam Double orderTotal) {

        if (orderTotal == null || !Double.isFinite(orderTotal) || orderTotal < 0) {
            return ResponseEntity.badRequest().body(Map.of(
                    "valid", false, "error", "El total del pedido es inválido."));
        }

        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new RuntimeException("Empresa no encontrada"));

        Coupon coupon = couponRepository.findByCodeIgnoreCaseAndCompany(code, company)
                .orElse(null);

        if (coupon == null) {
            return ResponseEntity.badRequest().body(Map.of("valid", false, "error", "Cupón no encontrado."));
        }
        if (!coupon.isActive()) {
            return ResponseEntity.badRequest().body(Map.of("valid", false, "error", "Este cupón no está activo."));
        }
        if (coupon.getExpiresAt() != null && coupon.getExpiresAt().isBefore(LocalDateTime.now())) {
            return ResponseEntity.badRequest().body(Map.of("valid", false, "error", "Este cupón ha vencido."));
        }
        if (coupon.getUsageLimit() != null && coupon.getUsageCount() >= coupon.getUsageLimit()) {
            return ResponseEntity.badRequest().body(Map.of("valid", false, "error", "Este cupón ya alcanzó su límite de usos."));
        }
        if (coupon.getMinOrderAmount() != null && orderTotal < coupon.getMinOrderAmount()) {
            return ResponseEntity.badRequest().body(Map.of("valid", false, "error",
                    String.format("El pedido mínimo para este cupón es S/ %.2f.", coupon.getMinOrderAmount())));
        }

        // Calcular descuento
        double discount = coupon.getDiscountType() == DiscountType.PERCENTAGE
                ? orderTotal * (coupon.getDiscountValue() / 100)
                : Math.min(coupon.getDiscountValue(), orderTotal);

        double finalTotal = Math.max(orderTotal - discount, 0);

        return ResponseEntity.ok(Map.of(
                "valid",         true,
                "couponId",      coupon.getId(),
                "code",          coupon.getCode(),
                "discountType",  coupon.getDiscountType().name(),
                "discountValue", coupon.getDiscountValue(),
                "discount",      discount,
                "finalTotal",    finalTotal
        ));
    }
}
