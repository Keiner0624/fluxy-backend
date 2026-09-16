package com.fluxyBackend.service;

import com.fluxyBackend.controller.AuthResponse;
import com.fluxyBackend.entity.*;
import com.fluxyBackend.entity.VerificationChallenge.Purpose;
import com.fluxyBackend.entity.VerificationChallenge.Type;
import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.repository.SignupDraftRepository;
import com.fluxyBackend.repository.UserIdentityRepository;
import com.fluxyBackend.repository.UserRepository;
import com.fluxyBackend.security.Hashing;
import com.fluxyBackend.security.PasswordPolicy;
import com.fluxyBackend.security.SessionService;
import com.fluxyBackend.security.oauth.OidcTokenVerifier;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Registro verificado.
 *
 * 1. Se crea el usuario pendiente con los datos del negocio en un borrador.
 * 2. Se verifica el correo (siempre) y el celular por SMS (cuando hay proveedor).
 * 3. Recién entonces se crea la empresa y se abre la sesión.
 *
 * Con Google o Apple el correo ya viene verificado por el proveedor y el paso 1
 * pide solo los datos del negocio.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SignupService {

    private static final Duration DRAFT_TTL = Duration.ofHours(24);
    /** Registros que nunca se completaron: se limpian pasada una semana. */
    private static final Duration PENDING_RETENTION = Duration.ofDays(7);

    private final UserRepository userRepository;
    private final SignupDraftRepository draftRepository;
    private final UserIdentityRepository identityRepository;
    private final VerificationService verificationService;
    private final BusinessRegistrationService registrationService;
    private final SessionService sessionService;
    private final AuditService auditService;
    private final BCryptPasswordEncoder passwordEncoder;
    private final com.fluxyBackend.repository.VerificationChallengeRepository challengeRepository;

    public record SignupRequest(String fullName, String businessName, BusinessCategory category, String whatsapp,
                                String email, String password, String taxId, boolean termsAccepted) {}

    public record BusinessRequest(String businessName, BusinessCategory category, String whatsapp, String taxId,
                                  boolean termsAccepted) {}

    public record CompanySummary(Long id, String name, String slug, String plan) {}

    /** Estado del registro. signupToken solo viaja al iniciar o retomar; completed trae la sesión. */
    public record SignupState(String signupToken, String fullName, String email, String method,
                              boolean emailVerified, boolean phoneRequired, boolean phoneVerified, String phone,
                              boolean businessCompleted, List<String> pending, boolean completed,
                              AuthResponse session, CompanySummary company) {}

    // ─── Inicio ───────────────────────────────────────────────────────────────

    @Transactional
    public SignupState startWithPassword(SignupRequest request) {
        String email = normalizeEmail(request.email());
        String fullName = cleanName(request.fullName(), "Ingresá tu nombre (entre 2 y 80 caracteres).", 2, 80, "fullName");
        String phone = normalizePeruPhone(request.whatsapp());
        PasswordPolicy.validate(request.password(), email);
        BusinessRequest business = new BusinessRequest(request.businessName(), request.category(), request.whatsapp(),
                request.taxId(), request.termsAccepted());
        validateBusiness(business);

        User user = userRepository.findByEmailIgnoreCase(email).orElse(null);
        if (user != null && user.getStatus() != User.Status.PENDING_VERIFICATION) {
            throw new BusinessException(HttpStatus.CONFLICT, "EMAIL_ALREADY_REGISTERED",
                    "Ya existe una cuenta con este correo. Iniciá sesión.", Map.of("field", "email"));
        }
        // Un registro pendiente se puede rehacer: sin el código del correo nadie lo completa.
        if (user == null) user = new User();
        user.setEmail(email);
        user.setFullName(fullName);
        user.setFirstName(fullName.split(" ", 2)[0]);
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setPasswordEnabled(true);
        user.setPasswordChangedAt(LocalDateTime.now());
        user.setRole(Role.BUSINESS_OWNER);
        user.setStatus(User.Status.PENDING_VERIFICATION);
        user.setPhone(phone);
        user.setPhoneVerifiedAt(null);
        user.setEmailVerifiedAt(null);
        user = userRepository.saveAndFlush(user);

        String token = Hashing.randomToken();
        SignupDraft draft = draftRepository.findById(user.getId()).orElseGet(SignupDraft::new);
        draft.setUserId(user.getId());
        draft.setTokenHash(Hashing.sha256(token));
        draft.setAuthMethod(UserSession.AuthMethod.PASSWORD);
        draft.setExpiresAt(LocalDateTime.now().plus(DRAFT_TTL));
        applyBusiness(draft, business);
        draftRepository.save(draft);

        verificationService.issue(user.getId(), Type.EMAIL, Purpose.SIGN_UP, email, null, fullName);
        if (verificationService.phoneChannelAvailable()) {
            verificationService.issue(user.getId(), Type.PHONE, Purpose.SIGN_UP, phone, null, fullName);
        }
        return state(user, draft, token, null, null);
    }

    /**
     * Primer acceso con Google o Apple de alguien sin cuenta: se crea el usuario
     * pendiente y la identidad; falta completar el negocio.
     */
    @Transactional
    public SignupState startWithIdentity(OidcTokenVerifier.VerifiedIdentity identity, String fullNameHint) {
        if (!identity.emailVerified()) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "OAUTH_EMAIL_UNVERIFIED",
                    "Tu correo no está verificado en " + OidcTokenVerifier.label(identity.provider())
                            + ". Verificalo allí o registrate con correo y contraseña.");
        }
        String fullName = fullNameHint == null || fullNameHint.isBlank()
                ? identity.email().split("@")[0] : fullNameHint.strip().replaceAll("\\s+", " ");
        if (fullName.length() > 80) fullName = fullName.substring(0, 80);

        User user = new User();
        user.setEmail(identity.email());
        user.setFullName(fullName);
        user.setFirstName(fullName.split(" ", 2)[0]);
        // Contraseña inutilizable: esta cuenta entra con el proveedor hasta que defina una.
        user.setPassword(passwordEncoder.encode(Hashing.randomToken()));
        user.setPasswordEnabled(false);
        user.setRole(Role.BUSINESS_OWNER);
        user.setStatus(User.Status.PENDING_VERIFICATION);
        user.setEmailVerifiedAt(LocalDateTime.now());
        user = userRepository.saveAndFlush(user);

        identityRepository.save(new UserIdentity(user.getId(), identity.provider(), identity.subject(), identity.email()));

        String token = Hashing.randomToken();
        SignupDraft draft = new SignupDraft();
        draft.setUserId(user.getId());
        draft.setTokenHash(Hashing.sha256(token));
        draft.setAuthMethod(identity.provider() == UserIdentity.Provider.GOOGLE
                ? UserSession.AuthMethod.GOOGLE : UserSession.AuthMethod.APPLE);
        draft.setExpiresAt(LocalDateTime.now().plus(DRAFT_TTL));
        draftRepository.save(draft);
        return state(user, draft, token, null, null);
    }

    /** Un usuario pendiente que vuelve (login o proveedor): nuevo token para retomar donde quedó. */
    @Transactional
    public SignupState resume(User user) {
        String token = Hashing.randomToken();
        SignupDraft draft = draftRepository.findById(user.getId()).orElseGet(() -> {
            SignupDraft fresh = new SignupDraft();
            fresh.setUserId(user.getId());
            fresh.setAuthMethod(user.hasPassword() ? UserSession.AuthMethod.PASSWORD : UserSession.AuthMethod.GOOGLE);
            return fresh;
        });
        draft.setTokenHash(Hashing.sha256(token));
        draft.setExpiresAt(LocalDateTime.now().plus(DRAFT_TTL));
        draftRepository.save(draft);
        return state(user, draft, token, null, null);
    }

    // ─── Pasos ────────────────────────────────────────────────────────────────

    public SignupState state(String signupToken) {
        SignupDraft draft = draft(signupToken);
        return state(user(draft), draft, null, null, null);
    }

    @Transactional
    public SignupState saveBusiness(String signupToken, BusinessRequest request, HttpServletRequest http) {
        SignupDraft draft = draft(signupToken);
        User user = user(draft);
        validateBusiness(request);
        String phone = normalizePeruPhone(request.whatsapp());
        applyBusiness(draft, request);
        draftRepository.save(draft);

        boolean phoneChanged = !phone.equals(user.getPhone());
        user.setPhone(phone);
        if (phoneChanged) user.setPhoneVerifiedAt(null);
        userRepository.save(user);

        if (verificationService.phoneChannelAvailable() && user.getPhoneVerifiedAt() == null) {
            verificationService.issue(user.getId(), Type.PHONE, Purpose.SIGN_UP, phone, null, user.getFullName());
        }
        return completeIfReady(user, draft, http);
    }

    /** noRollbackFor: un código incorrecto tiene que sumar el intento aunque la respuesta sea error. */
    @Transactional(noRollbackFor = BusinessException.class)
    public SignupState verify(String signupToken, String channel, String code, HttpServletRequest http) {
        SignupDraft draft = draft(signupToken);
        User user = user(draft);
        Type type = parseChannel(channel);
        verificationService.verify(user.getId(), Purpose.SIGN_UP, type, code);

        if (type == Type.EMAIL) {
            user.setEmailVerifiedAt(LocalDateTime.now());
            auditService.recordSecurityEvent(null, user, null, AuditAction.EMAIL_VERIFIED, Map.of("flow", "signup"));
        } else {
            user.setPhoneVerifiedAt(LocalDateTime.now());
            auditService.recordSecurityEvent(null, user, null, AuditAction.PHONE_VERIFIED, Map.of("flow", "signup"));
        }
        userRepository.save(user);
        return completeIfReady(user, draft, http);
    }

    @Transactional
    public VerificationService.Issued resend(String signupToken, String channel) {
        SignupDraft draft = draft(signupToken);
        User user = user(draft);
        Type type = parseChannel(channel);
        if (type == Type.EMAIL) {
            if (user.isEmailVerified()) throw new BusinessException("Tu correo ya está verificado.");
            return verificationService.issue(user.getId(), Type.EMAIL, Purpose.SIGN_UP, user.getEmail(), null, user.getFullName());
        }
        if (!verificationService.phoneChannelAvailable()) {
            throw new BusinessException(HttpStatus.CONFLICT, "PHONE_CHANNEL_UNAVAILABLE",
                    "La verificación del celular todavía no está disponible.");
        }
        if (user.getPhone() == null) throw new BusinessException("Primero completá los datos del negocio.");
        if (user.getPhoneVerifiedAt() != null) throw new BusinessException("Tu celular ya está verificado.");
        return verificationService.issue(user.getId(), Type.PHONE, Purpose.SIGN_UP, user.getPhone(), null, user.getFullName());
    }

    // ─── Cierre ───────────────────────────────────────────────────────────────

    private SignupState completeIfReady(User user, SignupDraft draft, HttpServletRequest http) {
        List<String> pending = pending(user, draft);
        if (!pending.isEmpty()) return state(user, draft, null, null, null);

        Company company = registrationService.createCompanyFor(user, draft);
        draftRepository.delete(draft);
        auditService.record(company.getId(), user, AuditAction.ACCOUNT_CREATED, "COMPANY", company.getId(),
                Map.of("method", String.valueOf(draft.getAuthMethod()),
                        "phoneVerified", user.getPhoneVerifiedAt() != null));
        AuthResponse session = sessionService.create(user, UserSession.AuthMethod.SIGNUP, true, http);
        return state(user, null, null, session, company);
    }

    private List<String> pending(User user, SignupDraft draft) {
        List<String> pending = new ArrayList<>();
        if (draft == null || !draft.hasBusiness()) pending.add("BUSINESS");
        if (!user.isEmailVerified()) pending.add("EMAIL");
        if (verificationService.phoneChannelAvailable() && user.getPhone() != null && user.getPhoneVerifiedAt() == null) {
            pending.add("PHONE");
        }
        return pending;
    }

    private SignupState state(User user, SignupDraft draft, String token, AuthResponse session, Company company) {
        boolean completed = session != null;
        return new SignupState(token, user.getFullName(), user.getEmail(),
                draft == null || draft.getAuthMethod() == null ? null : draft.getAuthMethod().name(),
                user.isEmailVerified(), verificationService.phoneChannelAvailable(), user.getPhoneVerifiedAt() != null,
                user.getPhone() == null ? null : VerificationService.maskPhone(user.getPhone()),
                completed || (draft != null && draft.hasBusiness()),
                completed ? List.of() : pending(user, draft), completed, session,
                company == null ? null : new CompanySummary(company.getId(), company.getName(), company.getSlug(),
                        company.getPlan().name()));
    }

    /** Limpieza diaria de registros abandonados. */
    @Scheduled(cron = "0 0 4 * * *", zone = "America/Lima")
    @Transactional
    public void purgeAbandoned() {
        LocalDateTime cutoff = LocalDateTime.now().minus(PENDING_RETENTION);
        List<User> abandoned = userRepository.findByStatusAndCreatedAtBefore(User.Status.PENDING_VERIFICATION, cutoff);
        for (User user : abandoned) {
            if (user.getCompany() != null) continue;
            draftRepository.findById(user.getId()).ifPresent(draftRepository::delete);
            identityRepository.deleteByUserId(user.getId());
            challengeRepository.deleteByUserId(user.getId());
            userRepository.delete(user);
        }
        if (!abandoned.isEmpty()) log.info("Registros abandonados eliminados: {}", abandoned.size());
    }

    // ─── Validación ───────────────────────────────────────────────────────────

    private SignupDraft draft(String signupToken) {
        if (signupToken == null || signupToken.isBlank()) throw expired();
        SignupDraft draft = draftRepository.findByTokenHash(Hashing.sha256(signupToken)).orElseThrow(SignupService::expired);
        if (draft.getExpiresAt().isBefore(LocalDateTime.now())) throw expired();
        return draft;
    }

    private User user(SignupDraft draft) {
        User user = userRepository.findById(draft.getUserId()).orElseThrow(SignupService::expired);
        if (user.getStatus() != User.Status.PENDING_VERIFICATION) {
            throw new BusinessException(HttpStatus.CONFLICT, "SIGNUP_ALREADY_COMPLETED", "Tu cuenta ya está creada. Iniciá sesión.");
        }
        return user;
    }

    private static void validateBusiness(BusinessRequest request) {
        cleanName(request.businessName(), "Usá entre 2 y 120 caracteres para tu negocio.", 2, 120, "businessName");
        if (request.category() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Elegí el rubro de tu negocio.",
                    Map.of("field", "category"));
        }
        normalizePeruPhone(request.whatsapp());
        String taxId = request.taxId() == null ? "" : request.taxId().strip();
        if (!taxId.isEmpty() && !taxId.matches("\\d{8}|\\d{11}")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "El DNI tiene 8 dígitos y el RUC, 11.",
                    Map.of("field", "taxId"));
        }
        if (!request.termsAccepted()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "Aceptá los términos y la política de privacidad para continuar.", Map.of("field", "termsAccepted"));
        }
    }

    private static void applyBusiness(SignupDraft draft, BusinessRequest request) {
        draft.setBusinessName(request.businessName().strip().replaceAll("\\s+", " "));
        draft.setCategory(request.category());
        String taxId = request.taxId() == null ? null : request.taxId().strip();
        draft.setTaxId(taxId == null || taxId.isEmpty() ? null : taxId);
        draft.setTermsAcceptedAt(LocalDateTime.now());
    }

    private static String cleanName(String value, String message, int min, int max, String field) {
        String clean = value == null ? "" : value.strip().replaceAll("\\s+", " ");
        if (clean.length() < min || clean.length() > max) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, Map.of("field", field));
        }
        return clean;
    }

    static String normalizeEmail(String email) {
        String value = email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
        if (value.length() > 254 || !value.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Ingresá un correo válido.",
                    Map.of("field", "email"));
        }
        return value;
    }

    /** Celular peruano: 9 dígitos que empiezan con 9. Devuelve 51 + número. */
    public static String normalizePeruPhone(String phone) {
        String digits = phone == null ? "" : phone.replaceAll("\\D", "");
        if (digits.length() == 11 && digits.startsWith("51")) digits = digits.substring(2);
        if (!digits.matches("9\\d{8}")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "Ingresá un celular peruano de 9 dígitos que empiece con 9.", Map.of("field", "whatsapp"));
        }
        return "51" + digits;
    }

    private static Type parseChannel(String channel) {
        if ("EMAIL".equalsIgnoreCase(channel)) return Type.EMAIL;
        if ("PHONE".equalsIgnoreCase(channel) || "WHATSAPP".equalsIgnoreCase(channel)) return Type.PHONE;
        throw new BusinessException("El canal tiene que ser EMAIL o PHONE.");
    }

    private static BusinessException expired() {
        return new BusinessException(HttpStatus.GONE, "SIGNUP_EXPIRED",
                "El registro venció. Empezá de nuevo o iniciá sesión para retomarlo.");
    }
}
