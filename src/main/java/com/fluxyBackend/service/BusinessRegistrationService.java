package com.fluxyBackend.service;

import com.fluxyBackend.DTOs.RegisterBussinesRequest;
import com.fluxyBackend.entity.*;
import com.fluxyBackend.exception.RegistrationException;
import com.fluxyBackend.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class BusinessRegistrationService {
    private final UserRepository users;
    private final CompanyService companies;
    private final BusinessProfileRepository profiles;
    private final CompanySettingsRepository settings;
    private final BCryptPasswordEncoder encoder;
    private final EntityManager entityManager;
    private final Validator validator;

    public record Created(Company company, User user) {}

    @Transactional
    public Created create(RegisterBussinesRequest request) {
        request.fullName = clean(request.fullName);
        request.businesName = clean(request.businesName);
        request.email = request.email == null ? null : request.email.strip().toLowerCase(Locale.ROOT);
        request.taxId = request.taxId == null ? null : request.taxId.strip();
        request.whatssapp = request.whatssapp == null ? null : request.whatssapp.replaceAll("[\\s()-]", "");
        var violations = validator.validate(request);
        if (!violations.isEmpty()) {
            var violation = violations.iterator().next();
            throw new RegistrationException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    violation.getPropertyPath().toString(), violation.getMessage());
        }
        if (users.existsByEmailIgnoreCase(request.email)) throw duplicateEmail();
        String digits = request.whatssapp.replace("+", "");
        String phone = "+" + (digits.length() == 9 ? "51" + digits : digits);
        Company company = companies.createCompany(Company.builder()
                .name(request.businesName).email(request.email).phone(phone).build());
        User user = users.saveAndFlush(User.builder()
                .fullName(request.fullName).firstName(request.fullName.split(" ", 2)[0])
                .email(request.email).password(encoder.encode(request.password))
                // Keep the established security role; the membership is explicitly OWNER.
                .role(Role.BUSINESS_OWNER).company(company).build());
        settings.save(new CompanySettings(company.getId()));
        BusinessProfile profile = new BusinessProfile();
        profile.setCompanyId(company.getId());
        profile.setCategory(request.category);
        profile.setTaxId(request.taxId == null || request.taxId.isEmpty() ? null : request.taxId);
        profiles.save(profile);
        entityManager.persist(new Membership(user.getId(), company.getId()));
        entityManager.persist(new LegalAcceptance(user.getId(), company.getId()));
        entityManager.flush();
        return new Created(company, user);
    }

    public static RegistrationException duplicateEmail() {
        return new RegistrationException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED", "email",
                "Ya existe una cuenta con este correo. Inicia sesión.");
    }

    private String clean(String value) {
        return value == null ? null : value.strip().replaceAll("\\s+", " ");
    }
}
