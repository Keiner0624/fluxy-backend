package com.fluxyBackend.marketing.dto;

import java.time.OffsetDateTime;

/**
 * Segmento de clientes. customers cuenta a quienes no pidieron la baja; reachable, a los que
 * además tienen teléfono. Los conteos vienen null si el plan no incluye el segmento o si
 * dependen de una categoría todavía no elegida.
 */
public record SegmentView(String key, String label, String rule, boolean advanced, boolean available,
                          Long customers, Long reachable) {

    public record Customer(Long id, String name, String phone, long orders, double spent,
                           OffsetDateTime lastOrderAt) {}
}
