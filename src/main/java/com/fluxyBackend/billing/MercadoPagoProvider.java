package com.fluxyBackend.billing;

import com.fluxyBackend.exception.BusinessException;
import com.mercadopago.MercadoPagoConfig;
import com.mercadopago.client.payment.PaymentClient;
import com.mercadopago.client.preference.PreferenceBackUrlsRequest;
import com.mercadopago.client.preference.PreferenceClient;
import com.mercadopago.client.preference.PreferenceItemRequest;
import com.mercadopago.client.preference.PreferenceRequest;
import com.mercadopago.exceptions.MPApiException;
import com.mercadopago.exceptions.MPException;
import com.mercadopago.exceptions.MPInvalidWebhookSignatureException;
import com.mercadopago.resources.payment.Payment;
import com.mercadopago.resources.preference.Preference;
import com.mercadopago.webhook.WebhookSignatureValidator;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/** Checkout Pro de Mercado Pago: cada pago compra meses de un plan (sin cobros automáticos). */
@Slf4j
@Component
public class MercadoPagoProvider implements PaymentProvider {

    public static final String NAME = "MERCADO_PAGO";

    @Value("${mercadopago.access_token}")
    private String accessToken;

    @Value("${mercadopago.webhook_secret:}")
    private String webhookSecret;

    @Value("${app.frontend_url:https://fluxyweb.com}")
    private String frontendUrl;

    @Value("${app.backend_url:http://localhost:8080}")
    private String backendUrl;

    @PostConstruct
    void configureSdk() {
        MercadoPagoConfig.setAccessToken(accessToken);
        // Sin límites explícitos, un Mercado Pago lento dejaba colgado el hilo de la petición.
        MercadoPagoConfig.setConnectionTimeout(5_000);
        MercadoPagoConfig.setConnectionRequestTimeout(5_000);
        MercadoPagoConfig.setSocketTimeout(15_000);
        MercadoPagoConfig.setMaxConnections(20);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public CheckoutResult createCheckout(CheckoutCommand command) {
        PreferenceItemRequest item = PreferenceItemRequest.builder()
                .title(command.title())
                .quantity(1)
                .unitPrice(command.amount())
                .currencyId(command.currency())
                .build();
        PreferenceBackUrlsRequest backUrls = PreferenceBackUrlsRequest.builder()
                .success(frontendUrl + "/dashboard/plans?payment=approved&plan=" + command.plan().name())
                .failure(frontendUrl + "/dashboard/plans?payment=rejected")
                .pending(frontendUrl + "/dashboard/plans?payment=in_process")
                .build();
        PreferenceRequest request = PreferenceRequest.builder()
                .items(List.of(item))
                .backUrls(backUrls)
                .autoReturn("approved")
                .externalReference(command.externalReference())
                .notificationUrl(backendUrl + "/payments/webhook")
                .build();
        try {
            Preference preference = new PreferenceClient().create(request);
            return new CheckoutResult(preference.getId(), preference.getInitPoint(), preference.getSandboxInitPoint());
        } catch (MPException | MPApiException e) {
            log.error("Error creando preferencia de Mercado Pago", e);
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "PAYMENT_PROVIDER_ERROR",
                    "No se pudo iniciar el pago. Intentá de nuevo en unos minutos.");
        }
    }

    @Override
    public ProviderPayment fetchPayment(String paymentId) {
        try {
            Payment payment = new PaymentClient().get(Long.parseLong(paymentId));
            return new ProviderPayment(String.valueOf(payment.getId()), payment.getStatus(),
                    payment.getExternalReference(), payment.getTransactionAmount(), payment.getCurrencyId());
        } catch (MPException | MPApiException e) {
            log.error("No se pudo consultar el pago {} en Mercado Pago", paymentId, e);
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "PAYMENT_PROVIDER_ERROR", "No se pudo consultar el pago.");
        }
    }

    @Override
    public boolean verifyWebhook(String signature, String requestId, String dataId) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "WEBHOOK_NOT_CONFIGURED",
                    "MERCADOPAGO_WEBHOOK_SECRET no está configurado.");
        }
        try {
            WebhookSignatureValidator.validate(signature, requestId, dataId, webhookSecret);
            return true;
        } catch (MPInvalidWebhookSignatureException e) {
            return false;
        }
    }
}
