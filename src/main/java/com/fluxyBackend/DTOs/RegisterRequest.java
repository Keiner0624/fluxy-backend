package com.fluxyBackend.DTOs;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

@Schema(description = "Registro de un usuario en una empresa existente.")
public class RegisterRequest {
    @NotBlank
    @Schema(description = "Nombre completo.", example = "Ana Pérez")
    public String fullName;
    @NotBlank
    @Email
    @Schema(description = "Correo del usuario.", example = "cliente@example.com")
    public String email;
    @NotBlank
    @Size(min = 8, max = 72)
    @Schema(description = "Contraseña de 8 a 72 caracteres.", example = "MiClave123!", format = "password")
    public String password;
    @NotNull
    @Schema(description = "Identificador de una empresa existente.", example = "1")
    public Long companyId;
}
