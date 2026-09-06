package com.fluxyBackend.service;

import com.fluxyBackend.DTOs.RegisterBussinesRequest;
import com.fluxyBackend.DTOs.RegisterBussinesResponse;
import com.fluxyBackend.DTOs.LoginRequest;
import com.fluxyBackend.DTOs.RegisterRequest;
import com.fluxyBackend.controller.AuthResponse;
import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Role;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.repository.UserRepository;
import com.fluxyBackend.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PostConstruct;
import java.util.UUID;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final CompanyService companyService;

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
                .company(company) // 🔥 AQUÍ ESTÁ LA CLAVE
                .build();
        userRepository.save(user);
        return "Usuario registrado correctamente";
    }
    public AuthResponse login(LoginRequest request){
        String normalizedEmail = normalizeEmail(request.email);
        User user = userRepository.findByEmailIgnoreCase(normalizedEmail).orElse(null);

        // Se compara siempre, exista el usuario o no. Antes, un correo
        // inexistente respondia de inmediato y uno real tardaba lo que tarda
        // bcrypt: esa diferencia de tiempo permitia averiguar que cuentas
        // existen, aunque el mensaje de error fuera el mismo.
        String hash = user != null ? user.getPassword() : hashSenuelo;
        boolean passwordCorrecta = passwordEncoder.matches(request.password, hash);

        if (user == null || !passwordCorrecta) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales invalidas");
        }

        String token = jwtService.generateToken(user.getEmail());
        return new AuthResponse(token);
    }

    @Transactional
    public RegisterBussinesResponse registerBusiness(RegisterBussinesRequest request) {
        String normalizedEmail = normalizeEmail(request.email);
        String businessName = normalizeText(request.businesName);
        String whatsapp = normalizeText(request.whatssapp);

        User existingUser = userRepository.findByEmailIgnoreCase(normalizedEmail).orElse(null);
        if (existingUser != null) {
            if (!passwordEncoder.matches(request.password, existingUser.getPassword())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "El email ya existe. Inicia sesion con esa cuenta");
            }

            Company existingCompany = existingUser.getCompany();
            if (existingCompany == null) {
                Company company = Company.builder()
                        .name(businessName)
                        .email(normalizedEmail)
                        .phone(whatsapp)
                        .build();
                existingCompany = companyService.createCompany(company);
                existingUser.setCompany(existingCompany);
                existingUser.setRole(Role.BUSINESS_OWNER);
                existingUser = userRepository.save(existingUser);
            }

            String token = jwtService.generateToken(existingUser.getEmail());
            return buildRegisterBusinessResponse(existingCompany, existingUser, token);
        }

        Company company = Company.builder()
                .name(businessName)
                .email(normalizedEmail)
                .phone(whatsapp)
                .build();
        Company savedCompany = companyService.createCompany(company);

        User user = User.builder()
                .fullName(businessName)
                .firstName(businessName)
                .email(normalizedEmail)
                .password(passwordEncoder.encode(request.password))
                .role(Role.BUSINESS_OWNER)
                .company(savedCompany)
                .build();
        userRepository.save(user);

        String token = jwtService.generateToken(user.getEmail());
        return buildRegisterBusinessResponse(savedCompany, user, token);
    }

    private RegisterBussinesResponse buildRegisterBusinessResponse(Company company, User user,
                                                                   String token) {
        RegisterBussinesResponse response = new RegisterBussinesResponse();
        response.token = token;

        response.company = new RegisterBussinesResponse.CompanyInfo();
        response.company.id = company.getId();
        response.company.name = company.getName();
        response.company.slug = company.getSlug();
        response.company.storeUrl =
                "https://fluxy-frontend-react-xtsb.vercel.app/?store=" + company.getSlug();
        response.company.phone = company.getPhone();
        response.company.whatssapp = company.getPhone();

        response.user = new RegisterBussinesResponse.UserInfo();
        response.user.fullName = user.getFullName();
        response.user.email = user.getEmail();

        return response;
    }


    // ─── Login de administrador (sin cuenta en BD) ────────────────────────────
    public AuthResponse adminLogin(LoginRequest request) {
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
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales incorrectas.");
        }

        // Generar token con email de admin
        String token = jwtService.generateAdminToken(normalizeEmail(adminEmail));
        return new AuthResponse(token);
    }

    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;

    }
}
