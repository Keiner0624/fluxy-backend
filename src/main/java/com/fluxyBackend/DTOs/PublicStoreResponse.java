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
        boolean acceptingOrders
) {
    public static PublicStoreResponse from(Company company) {
        return from(company, null);
    }

    public static PublicStoreResponse from(Company company, CompanyIntegrations integrations) {
        return new PublicStoreResponse(
                company.getId(),
                company.getName(),
                company.getSlug(),
                company.getPhone(),
                company.getAddress(),
                company.getDescription(),
                company.getPrimaryColor(),
                company.getLogoUrl(),
                company.getStoreStyle(),
                company.getPaymentMethods(),
                company.getPlan(),
                integrations == null ? null : integrations.getGoogleAnalyticsId(),
                integrations == null ? null : integrations.getMetaPixelId(),
                integrations == null || integrations.isWhatsappEnabled(),
                company.getStatus() != Company.Status.SUSPENDED
        );
    }
}
