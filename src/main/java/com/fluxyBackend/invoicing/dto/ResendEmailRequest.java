package com.fluxyBackend.invoicing.dto;

/** Reenvío por correo; email opcional para mandarlo a otra dirección. */
public record ResendEmailRequest(String email) {
}
