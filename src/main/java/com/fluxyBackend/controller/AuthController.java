package com.fluxyBackend.controller;

import com.fluxyBackend.DTOs.LoginRequest;
import com.fluxyBackend.DTOs.RegisterBussinesRequest;
import com.fluxyBackend.DTOs.RegisterBussinesResponse;
import com.fluxyBackend.DTOs.RegisterRequest;
import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.repository.UserRepository;
import com.fluxyBackend.security.JwtService;
import com.fluxyBackend.service.AuthService;
import com.fluxyBackend.service.CompanyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final CompanyService companyService;
    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @PostMapping("/register")
    public String register(@RequestBody @Valid RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    public AuthResponse login(@RequestBody @Valid LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/register-business")
    public RegisterBussinesResponse registerBusiness(
            @RequestBody RegisterBussinesRequest request) {

        // 1. Crear empresa
        Company company = new Company();
        company.setName(request.businesName);
        company.setEmail(request.email);
        company.setPhone(request.whatssapp);
        Company savedCompany = companyService.createCompany(company);

        // 2. Crear usuario
        User user = new User();
        user.setFullName(request.businesName);
        user.setEmail(request.email);
        user.setPassword(passwordEncoder.encode(request.password));
        user.setCompany(savedCompany);
        userRepository.save(user);

        // 3. Generar token
        String token = jwtService.generateToken(request.email);

        // 4. Armar respuesta
        RegisterBussinesResponse response = new RegisterBussinesResponse();
        response.token = token;

        response.company = new RegisterBussinesResponse.CompanyInfo();
        response.company.id = savedCompany.getId();
        response.company.name = savedCompany.getName();
        response.company.slug = savedCompany.getSlug();
        response.company.storeUrl = "https://fluxy-frontend-react-xtsb.vercel.app/store/"
                + savedCompany.getSlug();

        response.user = new RegisterBussinesResponse.UserInfo();
        response.user.fullName = request.businesName;
        response.user.email = request.email;

        return response;
    }
}

