package com.fluxyBackend.security.access;

import java.util.EnumSet;
import java.util.Set;

import static com.fluxyBackend.security.access.Permission.*;

/** Rol de una persona dentro de una empresa y los permisos que trae por defecto. */
public enum MemberRole {
    OWNER,
    ADMIN,
    SELLER,
    VIEWER;

    public Set<Permission> defaultPermissions() {
        return switch (this) {
            case OWNER -> EnumSet.allOf(Permission.class);
            case ADMIN -> {
                Set<Permission> all = EnumSet.allOf(Permission.class);
                all.removeAll(Permission.OWNER_ONLY);
                yield all;
            }
            case SELLER -> EnumSet.of(
                    PRODUCT_VIEW,
                    ORDER_VIEW, ORDER_UPDATE, ORDER_CANCEL,
                    CUSTOMER_VIEW, CUSTOMER_UPDATE,
                    PAYMENT_VIEW, PAYMENT_UPDATE,
                    INVENTORY_VIEW,
                    COUPON_VIEW);
            case VIEWER -> EnumSet.of(
                    PRODUCT_VIEW, ORDER_VIEW, CUSTOMER_VIEW, PAYMENT_VIEW,
                    INVENTORY_VIEW, COUPON_VIEW, REPORT_VIEW);
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
