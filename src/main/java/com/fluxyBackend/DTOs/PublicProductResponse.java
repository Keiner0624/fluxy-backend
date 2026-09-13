package com.fluxyBackend.DTOs;

import com.fluxyBackend.entity.Category;
import com.fluxyBackend.entity.Prodcut;

/**
 * Producto tal como lo ve un comprador. Deja afuera costo, SKU, stock mínimo y
 * estado: son datos internos del negocio.
 */
public record PublicProductResponse(
        Long id,
        String name,
        double price,
        int stock,
        String imageUrl,
        String images,
        String description,
        PublicCategory category
) {
    public record PublicCategory(Long id, String name, String emoji) {}

    public static PublicProductResponse from(Prodcut p) {
        Category c = p.getCategory();
        return new PublicProductResponse(p.getId(), p.getName(), p.getPrice(), p.getStock(), p.getImageUrl(),
                p.getImages(), p.getDescription(),
                c == null ? null : new PublicCategory(c.getId(), c.getName(), c.getEmoji()));
    }
}
