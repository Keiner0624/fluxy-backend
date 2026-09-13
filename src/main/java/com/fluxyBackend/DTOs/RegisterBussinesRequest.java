package com.fluxyBackend.DTOs;

import io.swagger.v3.oas.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.AssertTrue;
import com.fluxyBackend.entity.BusinessCategory;

@Schema(description = "Registro de una tienda y su propietario.")
public class RegisterBussinesRequest {
    @NotBlank
    @Size(min = 2, max = 80)
    public String fullName;

    @NotNull
    public BusinessCategory category;

    @Pattern(regexp = "^([0-9]{8}|[0-9]{11})?$")
    public String taxId;

    @AssertTrue(message = "Debes aceptar los términos y la política de privacidad")
    public boolean termsAccepted;

    @NotBlank
    @Size(min = 2, max = 120)
    @JsonAlias("businessName")
    @Schema(description = "Nombre comercial. La clave JSON conserva la grafía businesName.", example = "Mi tienda")
    public String businesName;
    @NotBlank
    @JsonAlias("whatsapp")
    @Pattern(regexp = "^(?:\\+?51)?9[0-9]{8}$", message = "Ingresa un celular peruano de 9 dígitos")
    @Schema(description = "WhatsApp de contacto. También acepta el alias whatsapp.", example = "51987654321")
    public String whatssapp;
    @NotBlank
    @Email
    @Size(max = 254)
    @Schema(description = "Correo del propietario.", example = "cliente@example.com")
    public String email;
    @NotBlank
    @Size(min = 8, max = 72)
    @Schema(description = "Contraseña de 8 a 72 caracteres.", example = "MiClave123!", format = "password")
    public String password;

    @AssertTrue(message = "La contraseña no puede superar 72 bytes UTF-8")
    public boolean isPasswordWithinByteLimit() {
        return password == null || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 72;
    }
}
