package com.fluxyBackend.invoicing.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Configuración completa para quien la administra. Del proveedor solo se muestra una pista de la
 * ruta y los últimos 4 caracteres del token; las capacidades del perfil las calcula el servidor.
 */
public record InvoicingConfigurationView(String status, String statusReason, String environment, String provider, String providerLabel,
                                         String providerEndpointHint, String tokenHint, boolean hasCredentials, boolean requiresCredentials,
                                         boolean connectionOk, OffsetDateTime connectionCheckedAt, String connectionMessage,
                                         String taxId, String businessName, String tradeName, String fiscalAddress,
                                         boolean automaticIssuing, String issueTrigger, boolean emailEnabled, boolean attachPdf,
                                         boolean attachXml, boolean printAutomatically, int paperWidth, String taxAffectation,
                                         OffsetDateTime activatedAt, Profile profile, List<Series> series,
                                         List<CheckItem> checklist, boolean canActivate, boolean planAllowed, boolean rucLookupAvailable,
                                         List<String> providers) {

    public record Profile(String ruc, String businessName, String rucStatus, String rucCondition, String fiscalAddress,
                          String taxRegime, String verificationStatus, String verificationMessage,
                          String verificationSource, OffsetDateTime verifiedAt, OffsetDateTime lastCheckedAt,
                          boolean electronicIssuer, boolean canIssueReceipt, boolean canIssueInvoice) {}

    public record Series(Long id, String documentType, String series, long currentNumber, long nextNumber,
                         boolean enabled, boolean used, boolean allowed) {}

    /** Un requisito de la activación: ok y, si falta, qué hacer. */
    public record CheckItem(String key, String label, boolean ok, String message) {}
}
