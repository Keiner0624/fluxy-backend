package com.fluxyBackend.DTOs;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;

public class RegisterBussinesRequest {
    @NotBlank
    public String businesName;
    @NotBlank
    @JsonAlias("whatsapp")
    public String whatssapp;
    @NotBlank
    public String email;
    @NotBlank
    public String password;
}
