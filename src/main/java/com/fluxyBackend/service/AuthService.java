package com.fluxyBackend.service;

import com.fluxyBackend.DTOs.LoginRequest;
import com.fluxyBackend.DTOs.RegisterRequest;
import com.fluxyBackend.controller.AuthResponse;
import com.fluxyBackend.entity.*;
import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.exception.ForbiddenException;
import com.fluxyBackend.repository.MembershipRepository;
import com.fluxyBackend.repository.UserIdentityRepository;
import com.fluxyBackend.repository.UserRepository;
import com.fluxyBackend.security.JwtService;
import com.fluxyBackend.security.SecurityMonitor;
import com.fluxyBackend.security.SessionService;
import com.fluxyBackend.security.oauth.OAuthNonceService;
import com.fluxyBackend.security.oauth.OidcTokenVerifier;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final CompanyService companyService;
    private final MembershipRepository membershipRepository;
    private final UserIdentityRepository identityRepository;
    private final SessionService sessionService;
    private final SignupService signupService;
    private final OidcTokenVerifier oidcTokenVerifier;
    private final OAuthNonceService nonceService;
    private final AuditService auditService;
    private final SecurityMonitor monitor;
    private final EmailService emailService;

    /** Resultado del acceso con Google o Apple: sesión abierta, o registro por completar. */
    public record OAuthResult(String status, AuthResponse session, SignupService.SignupState signup) {}

    /**
     * Hash contra el que se compara cuando el correo no existe, para que el
     * login tarde lo mismo en ambos casos. Se genera al arrancar con el mismo
     * encoder, asi tiene el mismo coste que los hashes reales: uno fijo con
     * otro coste volveria a delatar la diferencia.
     */
    private String hashSenuelo;

    @PostConstruct
    void prepararHashSenuelo() {
        hashSenuelo = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Value("${admin.email:}")
    private String adminEmail;

    @Value("${admin.password:}")
    private String adminPassword;

    public String register(RegisterRequest request) {
        String normalizedEmail = normalizeEmail(request.email);
        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)){
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El email ya existe");
        }
        Company company = companyService.getById(request.companyId);
        User user = User.builder()
                .fullName(request.fullName)
                .email(normalizedEmail)
                .password(passwordEncoder.encode(request.password))
                .role(Role.BUSINESS_OWNER)
                .company(company)
                .build();
        userRepository.save(user);
        return "Usuario registrado correctamente";
    }

    @Transactional
    public AuthResponse login(LoginRequest request, HttpServletRequest http) {
        String normalizedEmail = normalizeEmail(request.email);
        User user = userRepository.findByEmailIgnoreCase(normalizedEmail).orElse(null);

        // Se compara siempre, exista el usuario o no. Antes, un correo
        // inexistente respondia de inmediato y uno real tardaba lo que tarda
        // bcrypt: esa diferencia de tiempo permitia averiguar que cuentas
        // existen, aunque el mensaje de error fuera el mismo.
        String hash = user != null ? user.getPassword() : hashSenuelo;
        boolean passwordCorrecta = passwordEncoder.matches(request.password, hash);

        if (user == null || !passwordCorrecta || !user.hasPassword()) {
            monitor.recordLogin(false);
            if (user != null) {
                auditService.recordSecurityEvent(user.getCompany() == null ? null : user.getCompany().getId(),
                        user, null, AuditAction.LOGIN_FAILED, Map.of("method", "PASSWORD"));
            }
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales invalidas");
        }

        // Todo lo que sigue ocurre solo con la contraseña correcta: a un tercero no le revela nada.
        ensureCanSignIn(user);
        AuthResponse session = sessionService.create(user, UserSession.AuthMethod.PASSWORD, request.rememberMe, http);
        monitor.recordLogin(true);
        auditService.record(user.getCompany().getId(), user, AuditAction.LOGIN_SUCCESS, "USER", user.getId(),
                Map.of("method", "PASSWORD", "rememberMe", request.rememberMe));
        return session;
    }

    @Transactional
    public OAuthResult oauth(UserIdentity.Provider provider, String idToken, String nonce, String nameHint,
                             boolean rememberMe, HttpServletRequest http) {
        nonceService.consume(nonce);
        OidcTokenVerifier.VerifiedIdentity identity = oidcTokenVerifier.verify(provider, idToken, nonce);

        UserIdentity linked = identityRepository.findByProviderAndProviderUserId(provider, identity.subject()).orElse(null);
        if (linked != null) {
            User user = userRepository.findById(linked.getUserId())
                    .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "OAUTH_TOKEN_INVALID", "Cuenta no encontrada."));
            linked.setLastUsedAt(LocalDateTime.now());
            identityRepository.save(linked);
            return signInWithIdentity(user, provider, rememberMe, http);
        }

        User existing = userRepository.findByEmailIgnoreCase(identity.email()).orElse(null);
        if (existing != null) {
            // Se vincula solo cuando el proveedor garantiza el correo y la cuenta ya lo tenía verificado:
            // entrar con ese Google/Apple prueba lo mismo que un código enviado a ese correo.
            boolean canAutoLink = identity.emailAuthoritative() && existing.isEmailVerified()
                    && identityRepository.findByUserIdAndProvider(existing.getId(), provider).isEmpty();
            if (!canAutoLink) {
                throw new BusinessException(HttpStatus.CONFLICT, "ACCOUNT_EXISTS",
                        "Ya tenés una cuenta con este correo. Iniciá sesión con tu contraseña y vinculá "
                                + OidcTokenVerifier.label(provider) + " desde Seguridad.");
            }
            UserIdentity created = new UserIdentity(existing.getId(), provider, identity.subject(), identity.email());
            created.setLastUsedAt(LocalDateTime.now());
            identityRepository.save(created);
            auditService.record(existing.getCompany() == null ? null : existing.getCompany().getId(), existing,
                    AuditAction.IDENTITY_LINKED, "USER", existing.getId(),
                    Map.of("provider", provider.name(), "by", "AUTO_EMAIL"));
            String label = OidcTokenVerifier.label(provider);
            String email = existing.getEmail();
            String name = existing.getFullName();
            CompletableFuture.runAsync(() -> emailService.sendSecurityNotice(email, name, "Vinculamos tu cuenta de " + label,
                    "Iniciaste sesión en Fluxy con " + label + " y la vinculamos a tu cuenta. "
                            + "Si no fuiste vos, cambiá tu contraseña y desvinculala desde Seguridad."));
            return signInWithIdentity(existing, provider, rememberMe, http);
        }
        return new OAuthResult("ONBOARDING_REQUIRED", null, signupService.startWithIdentity(identity, nameHint));
    }

    private OAuthResult signInWithIdentity(User user, UserIdentity.Provider provider, boolean rememberMe,
                                           HttpServletRequest http) {
        if (user.getStatus() == User.Status.PENDING_VERIFICATION) {
            return new OAuthResult("ONBOARDING_REQUIRED", null, signupService.resume(user));
        }
        ensureCanSignIn(user);
        UserSession.AuthMethod method = provider == UserIdentity.Provider.GOOGLE
                ? UserSession.AuthMethod.GOOGLE : UserSession.AuthMethod.APPLE;
        AuthResponse session = sessionService.create(user, method, rememberMe, http);
        monitor.recordLogin(true);
        auditService.record(user.getCompany().getId(), user, AuditAction.LOGIN_SUCCESS, "USER", user.getId(),
                Map.of("method", provider.name()));
        return new OAuthResult("LOGGED_IN", session, null);
    }

    /** Estado de la cuenta y de su acceso a la empresa, una vez probada la identidad. */
    private void ensureCanSignIn(User user) {
        if (user.getStatus() == User.Status.PENDING_VERIFICATION) {
            SignupService.SignupState state = signupService.resume(user);
            Map<String, Object> details = new HashMap<>();
            details.put("signupToken", state.signupToken());
            details.put("pending", state.pending());
            throw new BusinessException(HttpStatus.FORBIDDEN, ForbiddenException.VERIFICATION_REQUIRED,
                    "Tu cuenta todavía no está verificada. Te enviamos el código para terminar.", details);
        }
        if (user.getStatus() == User.Status.DISABLED) {
            throw new ForbiddenException(ForbiddenException.ACCESS_DISABLED, "Esta cuenta está bloqueada. Escribinos a soporte.");
        }
        if (user.getCompany() == null) {
            throw new ForbiddenException(ForbiddenException.NO_COMPANY, "Tu cuenta no está asociada a ningún negocio.");
        }
        if (user.getCompany().getStatus() == Company.Status.ANONYMIZED) {
            throw new ForbiddenException("ACCOUNT_CLOSED", "Este negocio fue eliminado.");
        }
        membershipRepository.findByUserIdAndCompanyId(user.getId(), user.getCompany().getId())
                .filter(m -> !m.isActive())
                .ifPresent(m -> {
                    throw new ForbiddenException(ForbiddenException.ACCESS_DISABLED,
                            "Tu acceso a este negocio fue desactivado. Consultá con el dueño.");
                });
    }

    // ─── Login de administrador (sin cuenta en BD) ────────────────────────────
    public AuthResponse adminLogin(LoginRequest request, HttpServletRequest http) {
        if (adminEmail.isBlank() || adminPassword.isBlank()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Credenciales de admin no configuradas.");
        }

        boolean emailMatches = MessageDigest.isEqual(
                normalizeEmail(request.email).getBytes(StandardCharsets.UTF_8),
                normalizeEmail(adminEmail).getBytes(StandardCharsets.UTF_8));
        boolean passwordMatches = MessageDigest.isEqual(
                request.password.getBytes(StandardCharsets.UTF_8),
                adminPassword.getBytes(StandardCharsets.UTF_8));
        if (!emailMatches || !passwordMatches) {
            monitor.recordLogin(false);
            auditService.recordSecurityEvent(null, null, "admin", AuditAction.LOGIN_FAILED, Map.of("method", "ADMIN"));
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales incorrectas.");
        }
        auditService.recordSecurityEvent(null, null, "admin", AuditAction.LOGIN_SUCCESS, Map.of("method", "ADMIN"));
        return new AuthResponse(jwtService.generateAdminToken(normalizeEmail(adminEmail)));
    }

    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
