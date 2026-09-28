package com.fluxyBackend.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Envío por la API de Brevo con un servidor falso: formato de la petición, adjuntos y errores. */
class EmailServiceTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> keys = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(201);
    private HttpServer brevo;
    private EmailService email;

    @BeforeEach
    void setUp() throws Exception {
        brevo = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        brevo.createContext("/", exchange -> {
            paths.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            keys.add(exchange.getRequestHeaders().getFirst("api-key"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] reply = (status.get() == 201 ? "{\"messageId\":\"<abc@smtp-relay.brevo.com>\"}"
                    : "{\"code\":\"unauthorized\",\"message\":\"Key not found\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), reply.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(reply);
            }
        });
        brevo.start();
        email = new EmailService();
        ReflectionTestUtils.setField(email, "brevoKey", " xkeysib-prueba ");
        ReflectionTestUtils.setField(email, "brevoUrl", "http://127.0.0.1:" + brevo.getAddress().getPort() + "/");
        ReflectionTestUtils.setField(email, "mailFrom", "notificaciones@fluxy.test");
        ReflectionTestUtils.setField(email, "mailFromName", "Fluxy");
    }

    @AfterEach
    void tearDown() {
        brevo.stop(0);
    }

    @Test
    void enviaPorLaApiDeBrevoConLaClaveEnLaCabecera() throws Exception {
        boolean sent = email.sendVerificationCode("ana@example.com", "Ana", "123456", "verificar tu correo", 10);

        assertThat(sent).isTrue();
        assertThat(paths).containsExactly("POST /v3/smtp/email");
        assertThat(keys).containsExactly("xkeysib-prueba");
        JsonNode body = json.readTree(bodies.get(0));
        assertThat(body.at("/sender/email").asString()).isEqualTo("notificaciones@fluxy.test");
        assertThat(body.at("/sender/name").asString()).isEqualTo("Fluxy");
        assertThat(body.at("/to/0/email").asString()).isEqualTo("ana@example.com");
        assertThat(body.at("/to/0/name").asString()).isEqualTo("Ana");
        assertThat(body.at("/subject").asString()).isNotBlank();
        assertThat(body.at("/htmlContent").asString()).contains("123456");
        assertThat(body.has("attachment")).isFalse();
    }

    @Test
    void losAdjuntosVanEnBase64() throws Exception {
        boolean sent = email.sendWithAttachments("ana@example.com", null, "Tu boleta", "<p>Hola</p>",
                List.of(new EmailService.Attachment("B001-1.pdf", "application/pdf", "PDF".getBytes(StandardCharsets.UTF_8))));

        assertThat(sent).isTrue();
        JsonNode body = json.readTree(bodies.get(0));
        assertThat(body.at("/to/0/name").isMissingNode()).isTrue();
        assertThat(body.at("/attachment/0/name").asString()).isEqualTo("B001-1.pdf");
        assertThat(body.at("/attachment/0/content").asString()).isEqualTo("UERG");
    }

    @Test
    void unRechazoDeBrevoDevuelveFalseYSinClaveNoLlama() {
        status.set(401);
        assertThat(email.sendWithAttachments("ana@example.com", "Ana", "Hola", "<p>x</p>", List.of())).isFalse();

        ReflectionTestUtils.setField(email, "brevoKey", "");
        paths.clear();
        assertThat(email.isConfigured()).isFalse();
        assertThat(email.sendWithAttachments("ana@example.com", "Ana", "Hola", "<p>x</p>", List.of())).isFalse();
        assertThat(email.sendTeamInvitationEmail("b@example.com", "Tienda", "Ana", "Vendedor", "https://x/invite/1")).isFalse();
        assertThat(paths).isEmpty();
    }
}
