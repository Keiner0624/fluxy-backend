package com.fluxyBackend.invoicing.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** "SON: CIENTO VEINTICUATRO CON 50/100 SOLES", como pide la representación impresa. */
public final class AmountInWords {

    private static final String[] UNITS = {"CERO", "UNO", "DOS", "TRES", "CUATRO", "CINCO", "SEIS", "SIETE", "OCHO", "NUEVE",
            "DIEZ", "ONCE", "DOCE", "TRECE", "CATORCE", "QUINCE", "DIECISÉIS", "DIECISIETE", "DIECIOCHO", "DIECINUEVE",
            "VEINTE", "VEINTIUNO", "VEINTIDÓS", "VEINTITRÉS", "VEINTICUATRO", "VEINTICINCO", "VEINTISÉIS", "VEINTISIETE",
            "VEINTIOCHO", "VEINTINUEVE"};
    private static final String[] TENS = {"", "", "", "TREINTA", "CUARENTA", "CINCUENTA", "SESENTA", "SETENTA", "OCHENTA", "NOVENTA"};
    private static final String[] HUNDREDS = {"", "CIENTO", "DOSCIENTOS", "TRESCIENTOS", "CUATROCIENTOS", "QUINIENTOS",
            "SEISCIENTOS", "SETECIENTOS", "OCHOCIENTOS", "NOVECIENTOS"};

    private AmountInWords() {
    }

    public static String soles(BigDecimal amount) {
        BigDecimal value = amount.setScale(2, RoundingMode.HALF_UP);
        long integer = value.longValue();
        int cents = value.remainder(BigDecimal.ONE).movePointRight(2).abs().intValue();
        return words(integer) + " CON " + String.format("%02d", cents) + "/100 SOLES";
    }

    static String words(long n) {
        if (n == 0) return "CERO";
        StringBuilder out = new StringBuilder();
        long millions = n / 1_000_000;
        long thousands = (n / 1000) % 1000;
        long rest = n % 1000;
        if (millions > 0) {
            out.append(millions == 1 ? "UN MILLÓN" : apocope(below1000((int) millions)) + " MILLONES");
        }
        if (thousands > 0) {
            if (!out.isEmpty()) out.append(' ');
            out.append(thousands == 1 ? "MIL" : apocope(below1000((int) thousands)) + " MIL");
        }
        if (rest > 0) {
            if (!out.isEmpty()) out.append(' ');
            out.append(below1000((int) rest));
        }
        return out.toString();
    }

    private static String below1000(int n) {
        if (n == 100) return "CIEN";
        int h = n / 100;
        int r = n % 100;
        StringBuilder out = new StringBuilder(HUNDREDS[h]);
        if (r > 0) {
            if (!out.isEmpty()) out.append(' ');
            if (r < 30) {
                out.append(UNITS[r]);
            } else {
                out.append(TENS[r / 10]);
                if (r % 10 > 0) out.append(" Y ").append(UNITS[r % 10]);
            }
        }
        return out.toString();
    }

    /** "VEINTIUNO MIL" → "VEINTIÚN MIL", "TREINTA Y UNO MIL" → "TREINTA Y UN MIL". */
    private static String apocope(String text) {
        if (text.endsWith("VEINTIUNO")) return text.substring(0, text.length() - 9) + "VEINTIÚN";
        if (text.endsWith("UNO")) return text.substring(0, text.length() - 3) + "UN";
        return text;
    }
}
