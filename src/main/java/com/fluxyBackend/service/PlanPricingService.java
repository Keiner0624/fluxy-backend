package com.fluxyBackend.service;

import com.fluxyBackend.entity.Company.Plan;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Tarifas de las suscripciones cobradas por Mercado Pago, expresadas en PEN.
 * PRO cuesta 39.00 al mes y BUSINESS 59.00; el total es precio mensual por meses.
 * Los contratos HTTP se documentan en MercadoPagoController.
 */
@Service
public class PlanPricingService {

    public static final String CURRENCY = "PEN";
    private static final int MAX_MONTHS = 12;
    private static final Map<Plan, BigDecimal> MONTHLY_PRICES = Map.of(
            Plan.PRO, new BigDecimal("39.00"),
            Plan.BUSINESS, new BigDecimal("59.00")
    );

    /**
     * Normaliza espacios y mayúsculas y acepta únicamente PRO o BUSINESS.
     * @throws ResponseStatusException con estado 400 si el plan es nulo o no es de pago
     */
    public Plan parsePlan(String rawPlan) {
        try {
            Plan plan = Plan.valueOf(rawPlan == null ? "" : rawPlan.trim().toUpperCase());
            if (!MONTHLY_PRICES.containsKey(plan)) {
                throw new IllegalArgumentException();
            }
            return plan;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Plan inválido");
        }
    }

    /**
     * Interpreta una duración de 1 a 12 meses; null equivale a un mes.
     * @throws ResponseStatusException con estado 400 si la duración no es válida
     */
    public int parseMonths(String rawMonths) {
        try {
            int months = Integer.parseInt(rawMonths == null ? "1" : rawMonths);
            if (months < 1 || months > MAX_MONTHS) {
                throw new NumberFormatException();
            }
            return months;
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "La cantidad de meses debe estar entre 1 y 12");
        }
    }

    /**
     * Calcula el importe total en PEN, sin descuentos por duración.
     * @throws IllegalArgumentException si el plan o la duración no son válidos
     */
    public BigDecimal total(Plan plan, int months) {
        BigDecimal monthlyPrice = MONTHLY_PRICES.get(plan);
        if (monthlyPrice == null || months < 1 || months > MAX_MONTHS) {
            throw new IllegalArgumentException("Plan o duración inválidos");
        }
        return monthlyPrice.multiply(BigDecimal.valueOf(months));
    }
}
