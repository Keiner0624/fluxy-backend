package com.fluxyBackend.service;

import com.fluxyBackend.DTOs.BusinessProfileRequest;
import com.fluxyBackend.entity.*;
import com.fluxyBackend.entity.BusinessProfile.*;
import com.fluxyBackend.exception.NotFoundException;
import com.fluxyBackend.exception.RegistrationException;
import com.fluxyBackend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BusinessProfileService {
    private final UserRepository users;
    private final BusinessProfileRepository profiles;
    private final CompanySettingsRepository settings;

    private Long companyId(String email) {
        User user = users.findByEmailIgnoreCase(email).orElseThrow(() -> new NotFoundException("Usuario no encontrado"));
        if (user.getCompany() == null) throw new NotFoundException("Empresa no encontrada");
        return user.getCompany().getId();
    }

    public record Details(BusinessProfile profile, CompanySettings settings) {}

    @Transactional(readOnly = true)
    public Details get(String email) {
        Long id = companyId(email);
        return new Details(profiles.findById(id).orElseGet(() -> emptyProfile(id)),
                settings.findById(id).orElseGet(() -> new CompanySettings(id)));
    }

    @Transactional
    public Details save(String email, BusinessProfileRequest request) {
        Long id = companyId(email);
        BusinessProfile profile = profiles.findById(id).orElseGet(() -> emptyProfile(id));
        if (request.category() != null) profile.setCategory(request.category());
        if (request.businessType() != null) profile.setBusinessType(request.businessType());
        if (request.employeeRange() != null) profile.setEmployeeRange(request.employeeRange());
        if (request.monthlyOrdersRange() != null) profile.setMonthlyOrdersRange(request.monthlyOrdersRange());
        if (request.salesChannels() != null) profile.setSalesChannels(request.salesChannels());
        if (request.goals() != null) profile.setGoals(request.goals());
        if (request.taxId() != null) profile.setTaxId(clean(request.taxId()));
        if (request.legalName() != null) profile.setLegalName(clean(request.legalName()));
        if (request.department() != null) profile.setDepartment(clean(request.department()));
        if (request.province() != null) profile.setProvince(clean(request.province()));
        if (request.district() != null) profile.setDistrict(clean(request.district()));
        if (request.taxAddress() != null) profile.setTaxAddress(clean(request.taxAddress()));
        if (request.openingHours() != null) profile.setOpeningHours(clean(request.openingHours()));
        if (request.instagram() != null) profile.setInstagram(clean(request.instagram()));
        if (request.facebook() != null) profile.setFacebook(clean(request.facebook()));
        if (request.website() != null) profile.setWebsite(clean(request.website()));
        if (request.deliveryMethods() != null) profile.setDeliveryMethods(clean(request.deliveryMethods()));

        if (request.action() == BusinessProfileRequest.Action.COMPLETE) {
            if (profile.getCategory() == null || profile.getBusinessType() == null) {
                throw new RegistrationException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "businessType",
                        "Selecciona el rubro y el tipo de negocio antes de finalizar.");
            }
            profile.setOnboardingStatus(Status.COMPLETED);
            profile.setOnboardingStep(Step.FINISHED);
        } else if (request.action() == BusinessProfileRequest.Action.SKIP) {
            if (profile.getOnboardingStatus() != Status.COMPLETED) profile.setOnboardingStatus(Status.SKIPPED);
        } else if (request.action() != BusinessProfileRequest.Action.SAVE_DETAILS
                && profile.getOnboardingStatus() != Status.COMPLETED) {
            profile.setOnboardingStatus(Status.IN_PROGRESS);
            profile.setOnboardingStep(request.action() == BusinessProfileRequest.Action.SAVE_PROFILE
                    ? Step.SALES_CHANNELS : Step.GOALS);
        }
        CompanySettings config = settings.findById(id).orElseGet(() -> settings.save(new CompanySettings(id)));
        return new Details(profiles.save(profile), config);
    }

    private BusinessProfile emptyProfile(Long id) {
        BusinessProfile profile = new BusinessProfile();
        profile.setCompanyId(id);
        return profile;
    }

    private String clean(String value) { return value.strip(); }
}
