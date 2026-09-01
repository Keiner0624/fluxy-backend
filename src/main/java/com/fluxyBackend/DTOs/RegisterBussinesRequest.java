package com.fluxyBackend.DTOs;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public class RegisterBussinesRequest {
    @NotBlank
    public String businesName;
    @NotBlank
    @JsonAlias("whatsapp")
    public String whatssapp;
    @NotBlank
    @Email
    public String email;
    @NotBlank
    @Size(min = 8, max = 72)
    public String password;
}
