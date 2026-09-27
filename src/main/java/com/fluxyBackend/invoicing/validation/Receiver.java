package com.fluxyBackend.invoicing.validation;

import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.invoicing.enums.DocumentType;
import com.fluxyBackend.invoicing.enums.IdentityDocumentType;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Datos del cliente para el comprobante, ya normalizados y validados.
 *
 * Reglas: la factura exige RUC válido y razón social; la boleta desde S/ 700 exige documento de
 * identidad (debajo de ese monto puede ir sin documento, a nombre del cliente o "Clientes varios").
 */
public record Receiver(IdentityDocumentType documentType, String documentNumber, String name, String address,
                       String email) {

    public static final BigDecimal BOLETA_IDENTIFICATION_THRESHOLD = new BigDecimal("700.00");
    public static final String GENERIC_NAME = "CLIENTES VARIOS";

    public static Receiver validate(DocumentType type, String docType, String docNumber, String name, String address,
                                    String email, BigDecimal total) {
        IdentityDocumentType idType = parse(docType);
        String number = clean(docNumber, 15);
        String cleanName = clean(name, 200);
        String cleanAddress = clean(address, 300);
        String cleanEmail = clean(email, 150);
        if (cleanEmail != null) {
            cleanEmail = cleanEmail.toLowerCase(Locale.ROOT);
            if (!cleanEmail.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                throw new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST, "RECEIVER_INVALID",
                        "El correo del cliente no es válido.");
            }
        }

        if (type == DocumentType.FACTURA) {
            if (idType != IdentityDocumentType.RUC) fail("La factura se emite a un RUC.");
            number = TaxIdValidator.digits(number);
            if (!TaxIdValidator.isValidRuc(number)) fail("El RUC del cliente no es válido.");
            if (cleanName == null) fail("Indicá la razón social del cliente.");
            return new Receiver(IdentityDocumentType.RUC, number, cleanName.toUpperCase(Locale.ROOT), cleanAddress, cleanEmail);
        }

        // Boleta
        if (idType == null) idType = number == null ? IdentityDocumentType.NINGUNO : IdentityDocumentType.DNI;
        switch (idType) {
            case DNI -> {
                number = TaxIdValidator.digits(number);
                if (!TaxIdValidator.isValidDni(number)) fail("El DNI tiene que tener 8 dígitos.");
            }
            case RUC -> {
                number = TaxIdValidator.digits(number);
                if (!TaxIdValidator.isValidRuc(number)) fail("El RUC del cliente no es válido.");
            }
            case CARNET_EXTRANJERIA, PASAPORTE -> {
                if (!TaxIdValidator.isValidForeignId(number)) fail("El número de documento no es válido.");
                number = number.toUpperCase(Locale.ROOT);
            }
            case NINGUNO -> number = null;
        }
        if (idType == IdentityDocumentType.NINGUNO && total != null
                && total.compareTo(BOLETA_IDENTIFICATION_THRESHOLD) >= 0) {
            fail("Para una boleta de S/ 700 o más hace falta el documento de identidad del cliente.");
        }
        if (cleanName == null) {
            if (idType != IdentityDocumentType.NINGUNO) fail("Indicá el nombre del cliente.");
            cleanName = GENERIC_NAME;
        }
        return new Receiver(idType, number, cleanName, cleanAddress, cleanEmail);
    }

    private static IdentityDocumentType parse(String value) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim().toUpperCase(Locale.ROOT);
        for (IdentityDocumentType t : IdentityDocumentType.values()) {
            if (t.name().equals(v) || t.sunatCode().equals(v)) return t;
        }
        if ("CE".equals(v)) return IdentityDocumentType.CARNET_EXTRANJERIA;
        throw new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST, "RECEIVER_INVALID",
                "Tipo de documento del cliente inválido.");
    }

    private static String clean(String value, int max) {
        if (value == null) return null;
        String v = value.strip().replaceAll("\\s+", " ");
        if (v.isEmpty()) return null;
        return v.length() > max ? v.substring(0, max) : v;
    }

    private static void fail(String message) {
        throw new BusinessException(org.springframework.http.HttpStatus.BAD_REQUEST, "RECEIVER_INVALID", message);
    }
}
