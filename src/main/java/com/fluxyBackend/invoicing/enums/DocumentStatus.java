package com.fluxyBackend.invoicing.enums;

import java.util.EnumSet;
import java.util.Set;

/**
 * DRAFT → PENDING → PROCESSING → ACCEPTED, con REJECTED (validación) o ERROR (falla técnica que se reintenta).
 * Un ACCEPTED no se edita: se corrige con una nota de crédito (CANCEL_PENDING → CANCELLED).
 */
public enum DocumentStatus {
    DRAFT, PENDING, PROCESSING, ACCEPTED, REJECTED, ERROR, CANCEL_PENDING, CANCELLED;

    /** Los que todavía ocupan el pedido: no se puede emitir otro comprobante de venta para él. */
    public static final Set<DocumentStatus> BLOCKING = EnumSet.of(DRAFT, PENDING, PROCESSING, ACCEPTED, ERROR, CANCEL_PENDING);

    /** Los que el procesador puede tomar. */
    public static final Set<DocumentStatus> WORKABLE = EnumSet.of(PENDING, ERROR, PROCESSING);

    public boolean isFinal() {
        return this == ACCEPTED || this == REJECTED || this == CANCELLED;
    }
}
