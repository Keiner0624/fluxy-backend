package com.fluxyBackend.security.access;

import java.util.EnumSet;
import java.util.Set;

import static com.fluxyBackend.security.access.Permission.*;

/** Rol de una persona dentro de una empresa y los permisos que trae por defecto. */
public enum MemberRole {
    OWNER,
    /** Todo el negocio salvo facturación. */
    ADMIN,
    /** Encargado: toda la operación diaria, sin equipo, integraciones ni configuración crítica. */
    MANAGER,
    /** Vendedor: pedidos, clientes y cobros. */
    SELLER,
    /** Almacén: stock y preparación de pedidos. */
    WAREHOUSE,
    /** Solo lectura. */
    VIEWER;

    public Set<Permission> defaultPermissions() {
        return switch (this) {
            case OWNER -> EnumSet.allOf(Permission.class);
            case ADMIN -> {
                Set<Permission> all = EnumSet.allOf(Permission.class);
                all.removeAll(Permission.OWNER_ONLY);
                yield all;
            }
            case MANAGER -> EnumSet.of(
                    PRODUCT_VIEW, PRODUCT_CREATE, PRODUCT_UPDATE, PRODUCT_DELETE,
                    ORDER_VIEW, ORDER_UPDATE, ORDER_CANCEL,
                    CUSTOMER_VIEW, CUSTOMER_UPDATE,
                    PAYMENT_VIEW, PAYMENT_UPDATE, PAYMENT_REFUND,
                    INVENTORY_VIEW, INVENTORY_ADJUST,
                    COUPON_VIEW, COUPON_MANAGE,
                    REPORT_VIEW, REPORT_EXPORT,
                    TEAM_VIEW, INTEGRATION_VIEW,
                    MARKETING_VIEW, MARKETING_CREATE, MARKETING_EDIT, MARKETING_PUBLISH, MARKETING_ANALYTICS);
            case SELLER -> EnumSet.of(
                    PRODUCT_VIEW,
                    ORDER_VIEW, ORDER_UPDATE, ORDER_CANCEL,
                    CUSTOMER_VIEW, CUSTOMER_UPDATE,
                    PAYMENT_VIEW, PAYMENT_UPDATE,
                    INVENTORY_VIEW,
                    COUPON_VIEW,
                    MARKETING_VIEW);
            case WAREHOUSE -> EnumSet.of(
                    PRODUCT_VIEW,
                    ORDER_VIEW, ORDER_UPDATE,
                    INVENTORY_VIEW, INVENTORY_ADJUST);
            case VIEWER -> EnumSet.of(
                    PRODUCT_VIEW, ORDER_VIEW, CUSTOMER_VIEW, PAYMENT_VIEW,
                    INVENTORY_VIEW, COUPON_VIEW, REPORT_VIEW,
                    MARKETING_VIEW);
        };
    }

    public static MemberRole parse(String value) {
        if (value == null) return VIEWER;
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return VIEWER;
        }
    }
}
