package com.fluxyBackend.DTOs;

import com.fluxyBackend.entity.Company;

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
        Company.Plan plan
) {
    public static PublicStoreResponse from(Company company) {
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
                company.getPlan()
        );
    }
}
