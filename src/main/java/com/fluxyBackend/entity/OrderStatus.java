package com.fluxyBackend.entity;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Ciclo de vida de un pedido:
 * PENDING → CONFIRMED → PREPARING → READY → SHIPPED → DELIVERED, con CANCELLED
 * posible mientras el pedido no se haya entregado.
 */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    PREPARING,
    READY,
    SHIPPED,
    DELIVERED,
    CANCELLED,

    /**
     * Estado anterior al flujo por etapas; equivale a DELIVERED. SchemaUpgrade
     * migra las filas al arrancar, pero se conserva para poder leer cualquier
     * fila que quede.
     */
    @Deprecated
    COMPLETED;

    /** Orden del flujo principal. Los pedidos solo avanzan, nunca retroceden. */
    public static final List<OrderStatus> FLOW =
            List.of(PENDING, CONFIRMED, PREPARING, READY, SHIPPED, DELIVERED);

    /**
     * Estados que cuentan como venta: el negocio ya aceptó el pedido. Uno
     * PENDING todavía puede no concretarse, y uno CANCELLED no se concretó.
     */
    public static final Set<OrderStatus> SALE =
            EnumSet.of(CONFIRMED, PREPARING, READY, SHIPPED, DELIVERED, COMPLETED);

    /** Pedidos aceptados que todavía no llegaron al cliente. */
    public static final Set<OrderStatus> IN_PROGRESS =
            EnumSet.of(CONFIRMED, PREPARING, READY, SHIPPED);

    public boolean isSale() {
        return SALE.contains(this);
    }

    public boolean isFinal() {
        return this == DELIVERED || this == COMPLETED || this == CANCELLED;
    }

    /**
     * Estados a los que se puede pasar desde este. Se permite saltar etapas
     * (una venta en mostrador va de PENDING a DELIVERED), nunca retroceder.
     */
    public List<OrderStatus> nextStatuses() {
        if (isFinal()) return List.of();
        int index = FLOW.indexOf(this);
        List<OrderStatus> next = new ArrayList<>(FLOW.subList(index + 1, FLOW.size()));
        next.add(CANCELLED);
        return next;
    }

    public boolean canMoveTo(OrderStatus target) {
        return target != null && nextStatuses().contains(target);
    }
}
