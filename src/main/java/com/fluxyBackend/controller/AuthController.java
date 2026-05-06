package com.fluxyBackend.controller;

import com.fluxyBackend.DTOs.LoginRequest;
import com.fluxyBackend.DTOs.RegisterBussinesRequest;
import com.fluxyBackend.DTOs.RegisterBussinesResponse;
import com.fluxyBackend.DTOs.RegisterRequest;
import com.fluxyBackend.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

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
            @RequestBody @Valid RegisterBussinesRequest request) {
        return authService.registerBusiness(request);
    }

    // ─── Login exclusivo para el administrador de Fluxy ──────────────────────
    @PostMapping("/admin-login")
    public AuthResponse adminLogin(@RequestBody @Valid LoginRequest request) {
        return authService.adminLogin(request);
    }
}
