package com.fluxyBackend.invoicing.validation;

import java.util.Set;

/** Formato de los documentos de identidad peruanos. Solo formato: la existencia del RUC la confirma FiscalVerificationService. */
public final class TaxIdValidator {

    private static final int[] RUC_WEIGHTS = {5, 4, 3, 2, 7, 6, 5, 4, 3, 2};
    private static final Set<String> RUC_PREFIXES = Set.of("10", "15", "16", "17", "20");

    private TaxIdValidator() {
    }

    /** 11 dígitos, prefijo válido y dígito verificador (módulo 11). */
    public static boolean isValidRuc(String ruc) {
        if (ruc == null || !ruc.matches("\\d{11}") || !RUC_PREFIXES.contains(ruc.substring(0, 2))) return false;
        int sum = 0;
        for (int i = 0; i < 10; i++) sum += (ruc.charAt(i) - '0') * RUC_WEIGHTS[i];
        int check = 11 - (sum % 11);
        if (check == 10) check = 0;
        if (check == 11) check = 1;
        return check == ruc.charAt(10) - '0';
    }

    public static boolean isValidDni(String dni) {
        return dni != null && dni.matches("\\d{8}");
    }

    /** Carnet de extranjería o pasaporte: alfanumérico corto. */
    public static boolean isValidForeignId(String value) {
        return value != null && value.matches("[A-Za-z0-9]{6,12}");
    }

    /** "20123456789" → "20******789", para auditoría y registros. */
    public static String mask(String ruc) {
        if (ruc == null || ruc.length() < 6) return ruc;
        return ruc.substring(0, 2) + "*".repeat(ruc.length() - 5) + ruc.substring(ruc.length() - 3);
    }

    public static String digits(String value) {
        return value == null ? null : value.replaceAll("\\D", "");
    }
}
