package com.fluxyBackend.service;

import com.fluxyBackend.entity.*;
import com.fluxyBackend.exception.RegistrationException;
import com.fluxyBackend.repository.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Crea la empresa de un registro ya verificado. Antes se creaba junto con el
 * usuario en el primer paso, y quedaban tiendas de correos que nadie controlaba.
 */
@Service
@RequiredArgsConstructor
public class BusinessRegistrationService {
    private final UserRepository users;
    private final CompanyService companies;
    private final BusinessProfileRepository profiles;
    private final CompanySettingsRepository settings;
    private final EntityManager entityManager;

    @Transactional
    public Company createCompanyFor(User user, SignupDraft draft) {
        String phone = user.getPhone() == null ? null : "+" + user.getPhone();
        Company company = companies.createCompany(Company.builder()
                .name(draft.getBusinessName())
                .email(user.getEmail())
                .phone(phone)
                .status(Company.Status.ACTIVE)
                .lastBusinessActivityAt(LocalDateTime.now())
                .build());

        user.setCompany(company);
        // Rol de seguridad de siempre; la pertenencia a la empresa es explícitamente OWNER.
        user.setRole(Role.BUSINESS_OWNER);
        user.setStatus(User.Status.ACTIVE);
        users.saveAndFlush(user);

        settings.save(new CompanySettings(company.getId()));
        BusinessProfile profile = new BusinessProfile();
        profile.setCompanyId(company.getId());
        profile.setCategory(draft.getCategory());
        profile.setTaxId(draft.getTaxId() == null || draft.getTaxId().isEmpty() ? null : draft.getTaxId());
        profiles.save(profile);
        entityManager.persist(new Membership(user.getId(), company.getId()));
        entityManager.persist(new LegalAcceptance(user.getId(), company.getId()));
        entityManager.flush();
        return company;
    }

    public static RegistrationException duplicateEmail() {
        return new RegistrationException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "email",
                "Ya existe una cuenta con este correo. Iniciá sesión.");
    }
}
