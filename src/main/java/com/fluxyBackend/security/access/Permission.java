package com.fluxyBackend.security.access;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/** Acciones que se pueden conceder a una persona del equipo, agrupadas por módulo. */
public enum Permission {
    PRODUCT_VIEW, PRODUCT_CREATE, PRODUCT_UPDATE, PRODUCT_DELETE,
    ORDER_VIEW, ORDER_UPDATE, ORDER_CANCEL,
    /** Clientes: ver, editar datos de contacto, crear a mano, notas internas y etiquetas. */
    CUSTOMER_VIEW, CUSTOMER_UPDATE, CUSTOMER_CREATE, CUSTOMER_NOTES, CUSTOMER_TAGS,
    PAYMENT_VIEW, PAYMENT_UPDATE, PAYMENT_REFUND,
    INVENTORY_VIEW, INVENTORY_ADJUST,
    COUPON_VIEW, COUPON_MANAGE,
    REPORT_VIEW, REPORT_EXPORT,
    TEAM_VIEW, TEAM_INVITE, TEAM_MANAGE,
    INTEGRATION_VIEW, INTEGRATION_MANAGE,
    /** Marketing: ver campañas, crearlas, editarlas, publicarlas (activar, pausar, finalizar) y ver resultados. */
    MARKETING_VIEW, MARKETING_CREATE, MARKETING_EDIT, MARKETING_PUBLISH, MARKETING_ANALYTICS,
    /** Comprobantes electrónicos: ver, emitir, reenviar por correo y emitir notas de crédito. */
    INVOICE_VIEW, INVOICE_CREATE, INVOICE_RESEND, INVOICE_CREDIT_NOTE,
    /** Configuración fiscal (RUC, proveedor, credenciales, activación) y series. */
    INVOICING_CONFIGURE, INVOICING_SERIES,
    /** Configuración, estilo y dominio de la tienda. */
    SETTINGS_MANAGE,
    /** Registro de actividad del negocio. */
    AUDIT_VIEW,
    /** Plan y facturación: solo el dueño. */
    BILLING_MANAGE;

    /** Permisos que nunca se conceden a alguien que no sea el dueño. */
    public static final Set<Permission> OWNER_ONLY = EnumSet.of(BILLING_MANAGE);

    /** Lee una lista separada por coma e ignora valores desconocidos. */
    public static Set<Permission> parse(String csv) {
        if (csv == null || csv.isBlank()) return EnumSet.noneOf(Permission.class);
        Set<Permission> result = EnumSet.noneOf(Permission.class);
        for (String part : csv.split(",")) {
            String name = part.trim();
            // STORE_MANAGE es el nombre anterior de SETTINGS_MANAGE.
            String canonical = "STORE_MANAGE".equals(name) ? "SETTINGS_MANAGE" : name;
            Arrays.stream(values()).filter(p -> p.name().equals(canonical)).findFirst().ifPresent(result::add);
        }
        return result;
    }

    public static String format(Set<Permission> permissions) {
        return permissions.stream().sorted().map(Enum::name)
                .collect(Collectors.joining(","));
    }

    public static Set<String> names(Set<Permission> permissions) {
        return permissions.stream().sorted().map(Enum::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
