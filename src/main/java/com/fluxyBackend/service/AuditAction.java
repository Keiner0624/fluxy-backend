package com.fluxyBackend.service;

/** Nombres de acción del registro de auditoría. */
public final class AuditAction {
    private AuditAction() {
    }

    // Acceso
    public static final String LOGIN_SUCCESS = "LOGIN_SUCCESS";
    public static final String LOGIN_FAILED = "LOGIN_FAILED";
    public static final String LOGIN_BLOCKED = "LOGIN_BLOCKED";
    public static final String LOGOUT = "LOGOUT";
    public static final String SESSION_REVOKED = "SESSION_REVOKED";
    public static final String SESSIONS_REVOKED_ALL = "SESSIONS_REVOKED_ALL";
    public static final String REFRESH_REUSE_DETECTED = "REFRESH_REUSE_DETECTED";
    public static final String REAUTHENTICATED = "REAUTHENTICATED";

    // Cuenta
    public static final String ACCOUNT_CREATED = "ACCOUNT_CREATED";
    public static final String EMAIL_VERIFIED = "EMAIL_VERIFIED";
    public static final String PHONE_VERIFIED = "PHONE_VERIFIED";
    public static final String EMAIL_CHANGED = "EMAIL_CHANGED";
    public static final String PHONE_CHANGED = "PHONE_CHANGED";
    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";
    public static final String PASSWORD_RESET_REQUESTED = "PASSWORD_RESET_REQUESTED";
    public static final String PASSWORD_RESET_COMPLETED = "PASSWORD_RESET_COMPLETED";
    public static final String IDENTITY_LINKED = "IDENTITY_LINKED";
    public static final String IDENTITY_UNLINKED = "IDENTITY_UNLINKED";
    public static final String TERMS_ACCEPTED = "TERMS_ACCEPTED";

    // Equipo
    public static final String TEAM_INVITED = "TEAM_INVITED";
    public static final String TEAM_INVITE_REVOKED = "TEAM_INVITE_REVOKED";
    public static final String TEAM_MEMBER_JOINED = "TEAM_MEMBER_JOINED";
    public static final String TEAM_MEMBER_UPDATED = "TEAM_MEMBER_UPDATED";
    public static final String OWNERSHIP_TRANSFER_STARTED = "OWNERSHIP_TRANSFER_STARTED";
    public static final String OWNERSHIP_TRANSFER_CANCELLED = "OWNERSHIP_TRANSFER_CANCELLED";
    public static final String OWNERSHIP_TRANSFERRED = "OWNERSHIP_TRANSFERRED";

    // Plan y empresa
    public static final String PLAN_CHANGED = "PLAN_CHANGED";
    public static final String SUBSCRIPTION_CANCEL_REQUESTED = "SUBSCRIPTION_CANCEL_REQUESTED";
    public static final String SUBSCRIPTION_REACTIVATED = "SUBSCRIPTION_REACTIVATED";
    public static final String SETTINGS_UPDATED = "SETTINGS_UPDATED";
    public static final String INTEGRATION_UPDATED = "INTEGRATION_UPDATED";
    public static final String COMPANY_STATUS_CHANGED = "COMPANY_STATUS_CHANGED";
    public static final String COMPANY_DELETION_REQUESTED = "COMPANY_DELETION_REQUESTED";
    public static final String COMPANY_DELETION_CANCELLED = "COMPANY_DELETION_CANCELLED";
    public static final String COMPANY_ANONYMIZED = "COMPANY_ANONYMIZED";
    public static final String DATA_EXPORTED = "DATA_EXPORTED";

    // Operación
    public static final String ORDER_CREATED = "ORDER_CREATED";
    public static final String ORDER_CANCELLED = "ORDER_CANCELLED";
    public static final String PAYMENT_REFUNDED = "PAYMENT_REFUNDED";
    public static final String INVENTORY_ADJUSTED = "INVENTORY_ADJUSTED";
    public static final String PRODUCT_DELETED = "PRODUCT_DELETED";
    public static final String PRODUCTS_BULK_CHANGED = "PRODUCTS_BULK_CHANGED";
    public static final String CATEGORY_DELETED = "CATEGORY_DELETED";
    public static final String COUPON_DELETED = "COUPON_DELETED";

    // Marketing
    public static final String CAMPAIGN_CREATED = "CAMPAIGN_CREATED";
    public static final String CAMPAIGN_UPDATED = "CAMPAIGN_UPDATED";
    public static final String CAMPAIGN_ACTIVATED = "CAMPAIGN_ACTIVATED";
    public static final String CAMPAIGN_PAUSED = "CAMPAIGN_PAUSED";
    public static final String CAMPAIGN_FINISHED = "CAMPAIGN_FINISHED";
    public static final String CAMPAIGN_ARCHIVED = "CAMPAIGN_ARCHIVED";
    public static final String CAMPAIGN_DELETED = "CAMPAIGN_DELETED";

    // Facturación electrónica
    public static final String INVOICING_SETTINGS_UPDATED = "INVOICING_SETTINGS_UPDATED";
    public static final String INVOICING_PROVIDER_CHANGED = "INVOICING_PROVIDER_CHANGED";
    public static final String INVOICING_CONNECTION_TESTED = "INVOICING_CONNECTION_TESTED";
    public static final String INVOICING_ACTIVATION_CHANGED = "INVOICING_ACTIVATION_CHANGED";
    public static final String TAX_PROFILE_VERIFICATION_STARTED = "TAX_PROFILE_VERIFICATION_STARTED";
    public static final String TAX_PROFILE_VERIFIED = "TAX_PROFILE_VERIFIED";
    public static final String TAX_PROFILE_REJECTED = "TAX_PROFILE_REJECTED";
    public static final String TAX_PROFILE_SUSPENDED = "TAX_PROFILE_SUSPENDED";
    public static final String INVOICE_SERIES_CHANGED = "INVOICE_SERIES_CHANGED";
    public static final String INVOICE_CREATED = "INVOICE_CREATED";
    public static final String INVOICE_ACCEPTED = "INVOICE_ACCEPTED";
    public static final String INVOICE_REJECTED = "INVOICE_REJECTED";
    public static final String INVOICE_FAILED = "INVOICE_FAILED";
    public static final String INVOICE_RETRIED = "INVOICE_RETRIED";
    public static final String INVOICE_EMAIL_RESENT = "INVOICE_EMAIL_RESENT";
    public static final String INVOICE_PUBLIC_LINK_REVOKED = "INVOICE_PUBLIC_LINK_REVOKED";
    public static final String CREDIT_NOTE_CREATED = "CREDIT_NOTE_CREATED";
}
