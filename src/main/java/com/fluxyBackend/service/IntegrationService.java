package com.fluxyBackend.service;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.CompanyIntegrations;
import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.repository.CompanyIntegrationsRepository;
import com.fluxyBackend.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class IntegrationService {

    // Formatos estrictos: estos valores terminan dentro de un <script> en la
    // tienda pública, así que no se acepta nada que no sea exactamente un ID.
    private static final Pattern GA_ID = Pattern.compile("^G-[A-Z0-9]{4,20}$");
    private static final Pattern PIXEL_ID = Pattern.compile("^\\d{8,20}$");

    private final CompanyIntegrationsRepository integrationsRepository;
    private final CompanyRepository companyRepository;

    @Value("${mercadopago.access_token:}")
    private String mercadoPagoToken;

    public record WhatsApp(boolean enabled, String number, boolean connected, boolean planIncludesAutomation) {}
    public record Tracking(String id, boolean connected) {}
    public record MercadoPago(boolean platformBillingConfigured, boolean storeCheckoutAvailable) {}
    public record Overview(WhatsApp whatsapp, Tracking googleAnalytics, Tracking metaPixel,
                           MercadoPago mercadoPago, boolean webhooksAvailable, OffsetDateTime updatedAt) {}

    public record WhatsAppRequest(Boolean enabled, String number) {}
    public record TrackingRequest(String id) {}

    public CompanyIntegrations settings(Long companyId) {
        return integrationsRepository.findById(companyId).orElseGet(() -> new CompanyIntegrations(companyId));
    }

    public boolean whatsappEnabled(Long companyId) {
        return settings(companyId).isWhatsappEnabled();
    }

    public Overview overview(Company company) {
        CompanyIntegrations settings = settings(company.getId());
        boolean paidPlan = company.getPlan() == Company.Plan.PRO || company.getPlan() == Company.Plan.BUSINESS;
        String phone = company.getPhone();
        return new Overview(
                new WhatsApp(settings.isWhatsappEnabled(), phone,
                        settings.isWhatsappEnabled() && phone != null && !phone.isBlank(), paidPlan),
                new Tracking(settings.getGoogleAnalyticsId(), settings.getGoogleAnalyticsId() != null),
                new Tracking(settings.getMetaPixelId(), settings.getMetaPixelId() != null),
                new MercadoPago(mercadoPagoToken != null && !mercadoPagoToken.isBlank(), false),
                false,
                BusinessClock.withOffset(settings.getUpdatedAt()));
    }

    @Transactional
    public Overview updateWhatsApp(Company company, WhatsAppRequest request) {
        CompanyIntegrations settings = settings(company.getId());
        if (request.enabled() != null) settings.setWhatsappEnabled(request.enabled());
        if (request.number() != null) {
            String digits = request.number().replaceAll("[\\s()+-]", "");
            if (!digits.isEmpty() && !digits.matches("^\\d{9,15}$")) {
                throw new BusinessException("El número de WhatsApp tiene que tener entre 9 y 15 dígitos.");
            }
            if (digits.isEmpty() && Boolean.TRUE.equals(request.enabled())) {
                throw new BusinessException("Para activar WhatsApp indicá un número.");
            }
            company.setPhone(digits.isEmpty() ? null : "+" + (digits.length() == 9 ? "51" + digits : digits));
            companyRepository.save(company);
        }
        integrationsRepository.save(settings);
        return overview(company);
    }

    @Transactional
    public Overview updateGoogleAnalytics(Company company, TrackingRequest request) {
        CompanyIntegrations settings = settings(company.getId());
        settings.setGoogleAnalyticsId(validated(request.id(), GA_ID,
                "El ID de Google Analytics tiene el formato G-XXXXXXXXXX."));
        integrationsRepository.save(settings);
        return overview(company);
    }

    @Transactional
    public Overview updateMetaPixel(Company company, TrackingRequest request) {
        CompanyIntegrations settings = settings(company.getId());
        settings.setMetaPixelId(validated(request.id(), PIXEL_ID,
                "El ID del píxel de Meta es un número de 8 a 20 dígitos."));
        integrationsRepository.save(settings);
        return overview(company);
    }

    /** Vacío desconecta; cualquier otro valor tiene que cumplir el formato. */
    private static String validated(String value, Pattern pattern, String message) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if (!pattern.matcher(normalized).matches()) throw new BusinessException(message);
        return normalized;
    }
}
