package com.fluxyBackend.DTOs;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public class RegisterRequest {
    @NotBlank
    public String fullName;
    @NotBlank
    @Email
    public String email;
    @NotBlank
    @Size(min = 8, max = 72)
    public String password;
    @NotNull
    public Long companyId;
}
