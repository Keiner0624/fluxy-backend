package com.fluxyBackend.DTOs;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Credenciales para iniciar sesión.")
public class LoginRequest {
    @Email
    @NotBlank
    @Size(max = 254)
    @Schema(description = "Correo del usuario.", example = "cliente@example.com")
    public String email;
    @NotBlank
    @Size(max = 200)
    @Schema(description = "Contraseña de la cuenta.", example = "MiClave123!", format = "password")
    public String password;
    @Schema(description = "Recordar este dispositivo: la sesión dura 14 días en lugar de 12 horas.", example = "true")
    public boolean rememberMe;
}
