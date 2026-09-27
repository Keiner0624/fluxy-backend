package com.fluxyBackend.invoicing.service;

import com.fluxyBackend.invoicing.enums.TaxAffectation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Importes del comprobante a partir de un pedido. Los precios de la tienda incluyen IGV.
 *
 * - El descuento del pedido (cupón) se reparte entre las líneas en proporción a su importe, así
 *   el total del comprobante es exactamente lo que pagó el cliente.
 * - La base y el IGV se calculan sobre el total del documento y la diferencia de redondeo va a la
 *   última línea: la suma de las líneas coincide al centavo con los totales.
 */
public final class DocumentCalculator {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private DocumentCalculator() {
    }

    public record Input(Long productId, String sku, String description, BigDecimal quantity, BigDecimal lineTotal) {}

    public record Line(Long productId, String sku, String description, BigDecimal quantity, BigDecimal unitPrice,
                       BigDecimal unitValue, BigDecimal subtotal, BigDecimal tax, BigDecimal total) {}

    public record Result(List<Line> lines, BigDecimal subtotal, BigDecimal tax, BigDecimal discount, BigDecimal total) {}

    public static BigDecimal money(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    /** finalTotal: lo que se cobró (ya con descuento). */
    public static Result calculate(List<Input> inputs, BigDecimal finalTotal, TaxAffectation affectation) {
        if (inputs.isEmpty()) throw new IllegalArgumentException("Sin líneas");
        BigDecimal gross = inputs.stream().map(Input::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal total = finalTotal.setScale(2, RoundingMode.HALF_UP);
        BigDecimal discount = gross.subtract(total);
        if (discount.signum() < 0) {
            // El pedido cobró más que la suma de sus líneas: no se inventa un recargo, se usan las líneas.
            discount = BigDecimal.ZERO;
            total = gross;
        }

        // 1. Total de cada línea con su parte del descuento.
        List<BigDecimal> lineTotals = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < inputs.size(); i++) {
            BigDecimal line = inputs.get(i).lineTotal();
            BigDecimal share;
            if (i == inputs.size() - 1) {
                share = discount.subtract(allocated);
            } else {
                share = gross.signum() == 0 ? BigDecimal.ZERO
                        : discount.multiply(line).divide(gross, 2, RoundingMode.HALF_UP);
                allocated = allocated.add(share);
            }
            lineTotals.add(line.subtract(share));
        }

        // 2. Base e impuesto del documento.
        BigDecimal rate = affectation.rate();
        BigDecimal subtotal = total.multiply(ONE_HUNDRED).divide(ONE_HUNDRED.add(rate.multiply(ONE_HUNDRED)), 2, RoundingMode.HALF_UP);
        BigDecimal tax = total.subtract(subtotal);

        // 3. Base e impuesto por línea; el redondeo lo absorbe la última.
        List<Line> lines = new ArrayList<>();
        BigDecimal baseSum = BigDecimal.ZERO;
        for (int i = 0; i < inputs.size(); i++) {
            Input in = inputs.get(i);
            BigDecimal lineTotal = lineTotals.get(i);
            BigDecimal base = i == inputs.size() - 1 ? subtotal.subtract(baseSum)
                    : lineTotal.multiply(ONE_HUNDRED).divide(ONE_HUNDRED.add(rate.multiply(ONE_HUNDRED)), 2, RoundingMode.HALF_UP);
            baseSum = baseSum.add(base);
            BigDecimal lineTax = lineTotal.subtract(base);
            BigDecimal quantity = in.quantity();
            lines.add(new Line(in.productId(), in.sku(), in.description(), quantity,
                    lineTotal.divide(quantity, 10, RoundingMode.HALF_UP),
                    base.divide(quantity, 10, RoundingMode.HALF_UP),
                    base, lineTax, lineTotal));
        }
        return new Result(lines, subtotal, tax, discount.setScale(2, RoundingMode.HALF_UP), total);
    }
}
