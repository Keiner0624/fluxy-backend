package com.fluxyBackend.marketing.enums;

import java.util.EnumSet;
import java.util.Set;

/** Pasos del embudo de una campaña. */
public enum CampaignEventType {
    /** Entrada a la tienda desde un enlace de la campaña. */
    VIEW,
    /** Vio el detalle de un producto. */
    PRODUCT_VIEW,
    /** Agregó un producto al carrito. */
    ADD_TO_CART,
    /** Abrió la confirmación del pedido. */
    CHECKOUT_STARTED,
    /** Se creó el pedido; lo registra el servidor, nunca el navegador. */
    ORDER_COMPLETED,
    /** Pago aprobado; reservado para cuando el checkout de la tienda cobre en línea. */
    PAYMENT_COMPLETED;

    /** Los que puede informar la tienda pública. */
    public static final Set<CampaignEventType> FROM_STORE = EnumSet.of(VIEW, PRODUCT_VIEW, ADD_TO_CART, CHECKOUT_STARTED);
}
