package com.fluxyBackend.DTOs;

import com.fluxyBackend.entity.Company;

/**
 * Lo que ve un visitante de la tienda pública.
 *
 * Los endpoints /store/** no requieren autenticación, así que devolvían la
 * entidad Company entera: el correo del comerciante, las fechas de facturación,
 * si ya usó la prueba gratuita y su dominio personalizado quedaban al alcance
 * de cualquiera que consultara la URL de una tienda.
 *
 * Aquí sólo van los campos que la tienda necesita para renderizarse. `plan` se
 * conserva porque el storefront lo usa para ocultar la marca de Fluxy en el
 * plan Business y el botón de WhatsApp en el plan Free.
 */
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
