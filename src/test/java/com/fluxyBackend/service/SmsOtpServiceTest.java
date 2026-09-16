package com.fluxyBackend.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Envío de códigos por SMS: armado del pedido, remitente y fallos del proveedor. */
class SmsOtpServiceTest {

    private static class FakeSms extends SmsOtpService {
        final List<Map<String, String>> sent = new ArrayList<>();
        int status = 201;

        @Override
        protected int post(String sid, Map<String, String> form) {
            sent.add(form);
            return status;
        }
    }

    private static FakeSms service(String from, String messagingService) {
        FakeSms sms = new FakeSms();
        ReflectionTestUtils.setField(sms, "accountSid", "AC123");
        ReflectionTestUtils.setField(sms, "authToken", "secreto");
        ReflectionTestUtils.setField(sms, "from", from);
        ReflectionTestUtils.setField(sms, "messagingServiceSid", messagingService);
        ReflectionTestUtils.setField(sms, "brand", "Fluxy");
        return sms;
    }

    @Test
    void sinCredencialesNoSeUsa() {
        SmsOtpService sms = service("", "");
        assertThat(sms.isConfigured()).isFalse();
        assertThat(sms.sendCode("51987654321", "123456", 10)).isFalse();
    }

    @Test
    void enviaElCodigoAlNumeroConCodigoDePais() {
        FakeSms sms = service("+15550001111", "");
        assertThat(sms.sendCode("51987654321", "482913", 10)).isTrue();

        Map<String, String> form = sms.sent.get(0);
        assertThat(form).containsEntry("To", "+51987654321").containsEntry("From", "+15550001111");
        assertThat(form.get("Body")).startsWith("482913 es tu codigo de Fluxy").contains("10 min");
        // Sin tildes ni emojis: un solo segmento GSM-7.
        assertThat(form.get("Body")).matches("[ -~]+").hasSizeLessThanOrEqualTo(160);
    }

    @Test
    void elMessagingServiceTienePrioridadSobreElNumero() {
        FakeSms sms = service("+15550001111", "MG999");
        sms.sendCode("51987654321", "111222", 10);
        assertThat(sms.sent.get(0)).containsEntry("MessagingServiceSid", "MG999").doesNotContainKey("From");
    }

    @Test
    void unRechazoDelProveedorNoCuentaComoEnviado() {
        FakeSms sms = service("+15550001111", "");
        sms.status = 400;
        assertThat(sms.sendCode("51987654321", "111222", 10)).isFalse();
    }
}
