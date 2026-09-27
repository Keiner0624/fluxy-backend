package com.fluxyBackend.invoicing.dto;

/** RUC a verificar. businessName solo se usa en modo de prueba sin padrón. */
public record VerifyRucRequest(String ruc, String businessName, String taxRegime) {
}
