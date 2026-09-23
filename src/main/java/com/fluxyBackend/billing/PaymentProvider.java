package com.fluxyBackend.billing;

import com.fluxyBackend.entity.Company.Plan;

import java.math.BigDecimal;

/**
 * Proveedor de cobros de planes. La lógica de suscripciones depende de esta interfaz y no del SDK:
 * cambiar de proveedor es escribir otra implementación.
 */
public interface PaymentProvider {

    String name();

    CheckoutResult createCheckout(CheckoutCommand command);

    /** Consulta el pago en el proveedor. Nunca se confía en los parámetros del retorno del navegador. */
    ProviderPayment fetchPayment(String paymentId);

    /** false si la firma no corresponde al secreto configurado. */
    boolean verifyWebhook(String signature, String requestId, String dataId);

    record CheckoutCommand(Long companyId, Plan plan, int months, BigDecimal amount, String currency, String title,
                           String externalReference) {}

    record CheckoutResult(String checkoutId, String checkoutUrl, String sandboxUrl) {}

    record ProviderPayment(String id, String status, String externalReference, BigDecimal amount, String currency) {
        public boolean approved() {
            return "approved".equalsIgnoreCase(status);
        }
    }
}
