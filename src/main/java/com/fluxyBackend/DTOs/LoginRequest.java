package com.fluxyBackend.DTOs;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Credenciales para iniciar sesión.")
public class LoginRequest {
    @Email
    @NotBlank
    @Schema(description = "Correo del usuario.", example = "cliente@example.com")
    public String email;
    @NotBlank
    @Schema(description = "Contraseña de la cuenta.", example = "MiClave123!", format = "password")
    public String password;
}
