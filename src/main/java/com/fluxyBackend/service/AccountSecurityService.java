package com.fluxyBackend.service;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.entity.UserIdentity;
import com.fluxyBackend.entity.VerificationChallenge;
import com.fluxyBackend.entity.VerificationChallenge.Purpose;
import com.fluxyBackend.entity.VerificationChallenge.Type;
import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.repository.CompanyRepository;
import com.fluxyBackend.repository.UserIdentityRepository;
import com.fluxyBackend.repository.UserRepository;
import com.fluxyBackend.security.PasswordPolicy;
import com.fluxyBackend.security.RateLimitService;
import com.fluxyBackend.security.SessionService;
import com.fluxyBackend.security.access.Member;
import com.fluxyBackend.security.access.Permission;
import com.fluxyBackend.security.oauth.OAuthNonceService;
import com.fluxyBackend.security.oauth.OidcTokenVerifier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Seguridad de la propia cuenta: identidad, contraseña, contacto verificado y
 * cuentas vinculadas. Los cambios sensibles exigen haber confirmado la
 * identidad hace poco (step-up).
 */
@Service
@RequiredArgsConstructor
public class AccountSecurityService {

    static final Duration RECENT_AUTH = Duration.ofMinutes(10);

    private final UserRepository userRepository;
    private final UserIdentityRepository identityRepository;
    private final CompanyRepository companyRepository;
    private final SessionService sessionService;
    private final VerificationService verificationService;
    private final BCryptPasswordEncoder passwordEncoder;
    private final OidcTokenVerifier oidcTokenVerifier;
    private final OAuthNonceService nonceService;
    private final RateLimitService rateLimitService;
    private final AuditService auditService;
    private final EmailService emailService;

    public record Overview(String email, boolean emailVerified, String phone, boolean phoneVerified,
                           boolean phoneVerificationAvailable, String phoneChannel, boolean hasPassword, List<String> linkedProviders,
                           Map<String, Boolean> providersEnabled) {}

    public record ReauthRequest(String password, String provider, String idToken, String nonce) {}
    public record PasswordRequest(String currentPassword, String newPassword, Boolean signOutOthers) {}
    public record ContactRequest(String value, Boolean applyToStore) {}
    public record CodeRequest(String code, Boolean applyToStore) {}
    public record IdentityRequest(String idToken, String nonce) {}

    public Overview overview(User user) {
        List<String> linked = identityRepository.findByUserId(user.getId()).stream()
                .map(i -> i.getProvider().name()).sorted().toList();
        return new Overview(user.getEmail(), user.isEmailVerified(),
                user.getPhone() == null ? null : VerificationService.maskPhone(user.getPhone()),
                user.getPhoneVerifiedAt() != null, verificationService.phoneChannelAvailable(),
                verificationService.phoneChannel(), user.hasPassword(),
                linked, Map.of("GOOGLE", oidcTokenVerifier.isEnabled(UserIdentity.Provider.GOOGLE),
                "APPLE", oidcTokenVerifier.isEnabled(UserIdentity.Provider.APPLE)));
    }

    // ─── Confirmar identidad ──────────────────────────────────────────────────

    @Transactional
    public void reauthenticate(User user, String sessionId, ReauthRequest request) {
        rateLimitService.check(RateLimitService.Bucket.SENSITIVE, "user:" + user.getId());
        if (request.password() != null && !request.password().isEmpty()) {
            if (!user.hasPassword() || !passwordEncoder.matches(request.password(), user.getPassword())) {
                auditService.recordSecurityEvent(companyId(user), user, null, AuditAction.LOGIN_FAILED,
                        Map.of("flow", "reauth"));
                throw new BusinessException(HttpStatus.UNAUTHORIZED, "REAUTH_FAILED", "La contraseña no es correcta.");
            }
        } else if (request.provider() != null) {
            UserIdentity.Provider provider = parseProvider(request.provider());
            nonceService.consume(request.nonce());
            OidcTokenVerifier.VerifiedIdentity identity = oidcTokenVerifier.verify(provider, request.idToken(), request.nonce());
            identityRepository.findByUserIdAndProvider(user.getId(), provider)
                    .filter(linked -> linked.getProviderUserId().equals(identity.subject()))
                    .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "REAUTH_FAILED",
                            "Esa cuenta de " + OidcTokenVerifier.label(provider) + " no está vinculada a tu usuario."));
        } else {
            throw new BusinessException("Confirmá tu identidad con tu contraseña o con tu cuenta vinculada.");
        }
        sessionService.markReauthenticated(sessionId);
        auditService.recordSecurityEvent(companyId(user), user, null, AuditAction.REAUTHENTICATED, null);
    }

    // ─── Contraseña ───────────────────────────────────────────────────────────

    @Transactional
    public int changePassword(User user, String sessionId, PasswordRequest request) {
        rateLimitService.check(RateLimitService.Bucket.SENSITIVE, "user:" + user.getId());
        if (user.hasPassword()) {
            if (request.currentPassword() == null || !passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
                throw new BusinessException(HttpStatus.UNAUTHORIZED, "REAUTH_FAILED", "La contraseña actual no es correcta.");
            }
        } else {
            // Cuenta creada con Google o Apple: definir contraseña exige identidad reciente.
            sessionService.requireRecentAuth(sessionId, RECENT_AUTH);
        }
        PasswordPolicy.validate(request.newPassword(), user.getEmail());
        if (user.hasPassword() && passwordEncoder.matches(request.newPassword(), user.getPassword())) {
            throw new BusinessException("La nueva contraseña tiene que ser distinta de la actual.");
        }
        user.setPassword(passwordEncoder.encode(request.newPassword()));
        user.setPasswordEnabled(true);
        user.setPasswordChangedAt(LocalDateTime.now());
        userRepository.save(user);

        int revoked = request.signOutOthers() == null || request.signOutOthers()
                ? sessionService.revokeAllForUser(user.getId(), sessionId, "PASSWORD_CHANGED") : 0;
        auditService.record(companyId(user), user, AuditAction.PASSWORD_CHANGED, "USER", user.getId(),
                Map.of("sessionsRevoked", revoked));
        notify(user, "Tu contraseña cambió", "La contraseña de tu cuenta de Fluxy se cambió.");
        return revoked;
    }

    // ─── Correo ───────────────────────────────────────────────────────────────

    /** Cuentas existentes con el correo sin verificar. */
    @Transactional
    public VerificationService.Issued sendEmailVerification(User user) {
        if (user.isEmailVerified()) throw new BusinessException("Tu correo ya está verificado.");
        return verificationService.issue(user.getId(), Type.EMAIL, Purpose.VERIFY_EMAIL, user.getEmail(), null, user.getFullName());
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public void confirmEmailVerification(User user, String code) {
        verificationService.verify(user.getId(), Purpose.VERIFY_EMAIL, Type.EMAIL, code);
        user.setEmailVerifiedAt(LocalDateTime.now());
        userRepository.save(user);
        auditService.recordSecurityEvent(companyId(user), user, null, AuditAction.EMAIL_VERIFIED, null);
    }

    @Transactional
    public VerificationService.Issued requestEmailChange(User user, String sessionId, String newEmail) {
        sessionService.requireRecentAuth(sessionId, RECENT_AUTH);
        String email = SignupService.normalizeEmail(newEmail);
        if (email.equalsIgnoreCase(user.getEmail())) throw new BusinessException("Ese ya es tu correo.");
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw BusinessException.conflict("EMAIL_ALREADY_REGISTERED", "Ese correo ya está en uso por otra cuenta.");
        }
        return verificationService.issue(user.getId(), Type.EMAIL, Purpose.CHANGE_EMAIL, email, email, user.getFullName());
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public void confirmEmailChange(User user, String code) {
        VerificationChallenge challenge = verificationService.verify(user.getId(), Purpose.CHANGE_EMAIL, Type.EMAIL, code);
        String newEmail = challenge.getPendingValue();
        challenge.setPendingValue(null);
        if (userRepository.existsByEmailIgnoreCase(newEmail)) {
            throw BusinessException.conflict("EMAIL_ALREADY_REGISTERED", "Ese correo ya está en uso por otra cuenta.");
        }
        String oldEmail = user.getEmail();
        user.setEmail(newEmail);
        user.setEmailVerifiedAt(LocalDateTime.now());
        userRepository.save(user);
        auditService.record(companyId(user), user, AuditAction.EMAIL_CHANGED, "USER", user.getId(),
                Map.of("from", VerificationService.maskEmail(oldEmail), "to", VerificationService.maskEmail(newEmail)));
        // Aviso al correo anterior: si no fue la persona, se entera por ahí.
        String name = user.getFullName();
        CompletableFuture.runAsync(() -> emailService.sendSecurityNotice(oldEmail, name, "Tu correo cambió",
                "El correo de tu cuenta de Fluxy ahora es " + VerificationService.maskEmail(newEmail) + "."));
    }

    // ─── Celular ──────────────────────────────────────────────────────────────

    @Transactional
    public VerificationService.Issued requestPhoneChange(User user, String sessionId, String phone) {
        sessionService.requireRecentAuth(sessionId, RECENT_AUTH);
        if (!verificationService.phoneChannelAvailable()) {
            throw new BusinessException(HttpStatus.CONFLICT, "PHONE_CHANNEL_UNAVAILABLE",
                    "La verificación del celular todavía no está disponible.");
        }
        String normalized = SignupService.normalizePeruPhone(phone);
        return verificationService.issue(user.getId(), Type.PHONE, Purpose.CHANGE_PHONE, normalized, normalized, user.getFullName());
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public void confirmPhoneChange(Member member, String code, boolean applyToStore) {
        User user = member.user();
        VerificationChallenge challenge = verificationService.verify(user.getId(), Purpose.CHANGE_PHONE, Type.PHONE, code);
        String phone = challenge.getPendingValue();
        challenge.setPendingValue(null);
        user.setPhone(phone);
        user.setPhoneVerifiedAt(LocalDateTime.now());
        userRepository.save(user);
        if (applyToStore && member.can(Permission.SETTINGS_MANAGE)) {
            Company company = member.company();
            company.setPhone("+" + phone);
            companyRepository.save(company);
        }
        auditService.record(member, AuditAction.PHONE_CHANGED, "USER", user.getId(),
                Map.of("to", VerificationService.maskPhone(phone), "appliedToStore", applyToStore));
        notify(user, "Tu celular cambió", "El celular de tu cuenta ahora es " + VerificationService.maskPhone(phone) + ".");
    }

    // ─── Cuentas vinculadas ───────────────────────────────────────────────────

    @Transactional
    public void linkIdentity(User user, String sessionId, String providerName, IdentityRequest request) {
        sessionService.requireRecentAuth(sessionId, RECENT_AUTH);
        UserIdentity.Provider provider = parseProvider(providerName);
        nonceService.consume(request.nonce());
        OidcTokenVerifier.VerifiedIdentity identity = oidcTokenVerifier.verify(provider, request.idToken(), request.nonce());
        if (identityRepository.findByUserIdAndProvider(user.getId(), provider).isPresent()) {
            throw BusinessException.conflict("IDENTITY_ALREADY_LINKED", "Ya tenés una cuenta de " + OidcTokenVerifier.label(provider) + " vinculada.");
        }
        identityRepository.findByProviderAndProviderUserId(provider, identity.subject()).ifPresent(other -> {
            throw BusinessException.conflict("IDENTITY_IN_USE", "Esa cuenta de " + OidcTokenVerifier.label(provider)
                    + " ya está vinculada a otro usuario de Fluxy.");
        });
        identityRepository.save(new UserIdentity(user.getId(), provider, identity.subject(), identity.email()));
        auditService.record(companyId(user), user, AuditAction.IDENTITY_LINKED, "USER", user.getId(),
                Map.of("provider", provider.name()));
        notify(user, "Vinculaste " + OidcTokenVerifier.label(provider),
                "Ahora podés iniciar sesión en Fluxy con tu cuenta de " + OidcTokenVerifier.label(provider) + ".");
    }

    @Transactional
    public void unlinkIdentity(User user, String sessionId, String providerName) {
        sessionService.requireRecentAuth(sessionId, RECENT_AUTH);
        UserIdentity.Provider provider = parseProvider(providerName);
        UserIdentity identity = identityRepository.findByUserIdAndProvider(user.getId(), provider)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "IDENTITY_NOT_FOUND", "No tenés esa cuenta vinculada."));
        long others = identityRepository.findByUserId(user.getId()).size() - 1;
        if (!user.hasPassword() && others == 0) {
            throw new BusinessException(HttpStatus.CONFLICT, "LAST_LOGIN_METHOD",
                    "Es tu único método de acceso. Definí una contraseña antes de desvincularlo.");
        }
        identityRepository.delete(identity);
        auditService.record(companyId(user), user, AuditAction.IDENTITY_UNLINKED, "USER", user.getId(),
                Map.of("provider", provider.name()));
    }

    // ─── Apoyo ────────────────────────────────────────────────────────────────

    private void notify(User user, String title, String text) {
        String email = user.getEmail();
        String name = user.getFullName();
        CompletableFuture.runAsync(() -> emailService.sendSecurityNotice(email, name, title, text));
    }

    private static Long companyId(User user) {
        return user.getCompany() == null ? null : user.getCompany().getId();
    }

    private static UserIdentity.Provider parseProvider(String value) {
        try {
            return UserIdentity.Provider.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "PROVIDER_UNKNOWN", "Proveedor desconocido.");
        }
    }
}
