package com.fluxyBackend.DTOs;

import jakarta.validation.constraints.NotBlank;

public class RegisterBussinesRequest {
    @NotBlank
    public String businesName;
    @NotBlank
    public String whatssapp;
    @NotBlank
    public String email;
    @NotBlank
    public String password;
}
