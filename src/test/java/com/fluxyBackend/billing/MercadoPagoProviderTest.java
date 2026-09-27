package com.fluxyBackend.billing;

import com.fluxyBackend.entity.Company.Plan;
import com.fluxyBackend.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Sin una URL pública a la que Mercado Pago avise, no se inicia un cobro que nunca activaría el plan. */
class MercadoPagoProviderTest {

    @Test
    void soloAceptaUnaUrlHttpsPublicaParaLosAvisos() {
        assertThat(MercadoPagoProvider.isPublicUrl("https://fluxy-backend.onrender.com")).isTrue();
        assertThat(MercadoPagoProvider.isPublicUrl("https://api.fluxyweb.com/")).isTrue();
        assertThat(MercadoPagoProvider.isPublicUrl("http://localhost:8080")).isFalse();
        assertThat(MercadoPagoProvider.isPublicUrl("https://localhost")).isFalse();
        assertThat(MercadoPagoProvider.isPublicUrl("http://fluxy-backend.onrender.com")).isFalse();
        assertThat(MercadoPagoProvider.isPublicUrl("")).isFalse();
        assertThat(MercadoPagoProvider.isPublicUrl(null)).isFalse();
    }

    @Test
    void sinConfiguracionNoIniciaElPagoYLoDiceClaro() {
        MercadoPagoProvider provider = new MercadoPagoProvider();
        ReflectionTestUtils.setField(provider, "accessToken", "APP_USR-test");
        ReflectionTestUtils.setField(provider, "backendUrl", "http://localhost:8080");
        ReflectionTestUtils.setField(provider, "frontendUrl", "https://fluxyweb.vercel.app");

        PaymentProvider.CheckoutCommand command = new PaymentProvider.CheckoutCommand(
                1L, Plan.BUSINESS, 1, new BigDecimal("59.00"), "PEN", "Plan Business — 1 mes", "ref-1");
        assertThatThrownBy(() -> provider.createCheckout(command))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "PAYMENTS_NOT_CONFIGURED");

        ReflectionTestUtils.setField(provider, "backendUrl", "https://fluxy-backend.onrender.com");
        ReflectionTestUtils.setField(provider, "accessToken", "");
        assertThatThrownBy(() -> provider.createCheckout(command))
                .hasFieldOrPropertyWithValue("code", "PAYMENTS_NOT_CONFIGURED");
    }
}
