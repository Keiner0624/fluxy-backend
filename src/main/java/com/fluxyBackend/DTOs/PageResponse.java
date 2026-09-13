package com.fluxyBackend.DTOs;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Página de resultados con un formato estable. Serializar PageImpl de Spring
 * directamente expone detalles internos que cambian entre versiones.
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    /** Pagina una lista ya filtrada y ordenada en memoria. */
    public static <T> PageResponse<T> slice(List<T> all, int page, int size) {
        int safeSize = Math.max(1, size);
        int safePage = Math.max(0, page);
        int from = Math.min(safePage * safeSize, all.size());
        int to = Math.min(from + safeSize, all.size());
        int totalPages = (int) Math.ceil(all.size() / (double) safeSize);
        return new PageResponse<>(List.copyOf(all.subList(from, to)), safePage, safeSize, all.size(), totalPages);
    }

    /** Limita el tamaño de página que puede pedir un cliente. */
    public static int clampSize(int size) {
        return Math.min(Math.max(size, 1), 100);
    }
}
