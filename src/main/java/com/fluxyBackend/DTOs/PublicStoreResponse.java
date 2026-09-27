package com.fluxyBackend.DTOs;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.CompanyIntegrations;

public record PublicStoreResponse(
        Long id,
        String name,
        String slug,
        String phone,
        String address,
        String description,
        /* Sección Nosotros; null si el negocio no la escribió. */
        String aboutText,
        String primaryColor,
        String logoUrl,
        String storeStyle,
        String paymentMethods,
        Company.Plan plan,
        /* Integraciones que la tienda carga en el navegador del comprador. */
        String googleAnalyticsId,
        String metaPixelId,
        boolean whatsappEnabled,
        /* false mientras la tienda está suspendida: se ve el catálogo pero no se aceptan pedidos. */
        boolean acceptingOrders,
        /* Comprobantes que el cliente puede pedir al comprar; null si la tienda no emite. */
        InvoicingOptions invoicing
) {
    public record InvoicingOptions(boolean receipt, boolean invoice, boolean test) {}

    public static PublicStoreResponse from(Company company) {
        return from(company, null);
    }

    public static PublicStoreResponse from(Company company, CompanyIntegrations integrations) {
        return from(company, integrations, null);
    }

    public static PublicStoreResponse from(Company company, CompanyIntegrations integrations, InvoicingOptions invoicing) {
        return new PublicStoreResponse(
                company.getId(),
                company.getName(),
                company.getSlug(),
                company.getPhone(),
                company.getAddress(),
                company.getDescription(),
                company.getAboutText(),
                company.getPrimaryColor(),
                company.getLogoUrl(),
                // Sin el plan, la tienda se ve con el estilo por defecto; el del vendedor queda guardado.
                com.fluxyBackend.billing.PlanCatalog.has(company, com.fluxyBackend.billing.Feature.CUSTOM_STYLE)
                        ? company.getStoreStyle() : null,
                company.getPaymentMethods(),
                com.fluxyBackend.billing.PlanCatalog.effectivePlan(company),
                integrations == null ? null : integrations.getGoogleAnalyticsId(),
                integrations == null ? null : integrations.getMetaPixelId(),
                integrations == null || integrations.isWhatsappEnabled(),
                company.getStatus() != Company.Status.SUSPENDED,
                invoicing
        );
    }
}
