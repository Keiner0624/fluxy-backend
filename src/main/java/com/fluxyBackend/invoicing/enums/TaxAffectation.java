package com.fluxyBackend.invoicing.enums;

import java.math.BigDecimal;

/** Afectación al IGV de las ventas del negocio (catálogo 07). Los precios de la tienda siempre incluyen el impuesto. */
public enum TaxAffectation {
    GRAVADO("10", new BigDecimal("0.18")),
    EXONERADO("20", BigDecimal.ZERO),
    INAFECTO("30", BigDecimal.ZERO);

    private final String sunatCode;
    private final BigDecimal rate;

    TaxAffectation(String sunatCode, BigDecimal rate) {
        this.sunatCode = sunatCode;
        this.rate = rate;
    }

    public String sunatCode() {
        return sunatCode;
    }

    public BigDecimal rate() {
        return rate;
    }
}
