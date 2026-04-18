package com.fluxyBackend.DTOs;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class RegisterRequest {
    @NotBlank
    public String fullName;
    @NotBlank
    public String email;
    @NotBlank
    public String password;
    @NotNull
    public Long companyId;
}
