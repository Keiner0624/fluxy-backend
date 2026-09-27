package com.fluxyBackend.invoicing.provider.implementations;

import com.fluxyBackend.invoicing.enums.*;
import com.fluxyBackend.invoicing.provider.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Contrato con Nubefact contra un servidor falso: lo que se envía y cómo se interpreta la respuesta. */
class NubefactBillingProviderTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private HttpServer server;
    private final List<JsonNode> requests = new ArrayList<>();
    private final List<String> auth = new ArrayList<>();
    private Function<JsonNode, Object[]> responder;
    private NubefactBillingProvider provider;
    private String endpoint;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/cuenta", exchange -> {
            JsonNode body = json.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.add(body);
            auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            Object[] reply = responder.apply(body);
            byte[] bytes = ((String) reply[1]).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders((Integer) reply[0], bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        endpoint = "http://localhost:" + server.getAddress().getPort() + "/api/v1/cuenta";
        provider = new NubefactBillingProvider(json, "nubefact.com", true, 3);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void enviaElComprobanteConElFormatoDeNubefactYLeeLaAceptacion() {
        responder = body -> new Object[]{200, """
                {"aceptada_por_sunat": true, "sunat_description": "La Boleta numero B001-7, ha sido aceptada",
                 "sunat_responsecode": "0", "codigo_hash": "abc123=", "cadena_para_codigo_qr": "20600000005|03|B001|7|",
                 "enlace_del_pdf": "https://www.nubefact.com/cpe/x.pdf", "enlace_del_xml": "https://www.nubefact.com/cpe/x.xml"}"""};
        ProviderResult result = provider.issue(context(), boleta());

        assertThat(result.outcome()).isEqualTo(ProviderResult.Outcome.ACCEPTED);
        assertThat(result.hash()).isEqualTo("abc123=");
        assertThat(result.pdfRef()).endsWith(".pdf");
        JsonNode sent = requests.get(0);
        assertThat(auth.get(0)).isEqualTo("Token token=\"token-de-prueba-1234567890\"");
        assertThat(sent.path("operacion").asString()).isEqualTo("generar_comprobante");
        assertThat(sent.path("tipo_de_comprobante").asInt()).isEqualTo(2);
        assertThat(sent.path("serie").asString()).isEqualTo("B001");
        assertThat(sent.path("numero").asLong()).isEqualTo(7);
        assertThat(sent.path("cliente_tipo_de_documento").asString()).isEqualTo("1");
        assertThat(sent.path("fecha_de_emision").asString()).isEqualTo("26-09-2026");
        assertThat(sent.path("total_gravada").decimalValue()).isEqualByComparingTo("50.85");
        assertThat(sent.path("total_igv").decimalValue()).isEqualByComparingTo("9.15");
        assertThat(sent.path("total").decimalValue()).isEqualByComparingTo("60.00");
        assertThat(sent.path("enviar_automaticamente_al_cliente").asBoolean()).isFalse();
        assertThat(sent.path("items").get(0).path("tipo_de_igv").asInt()).isEqualTo(1);
        assertThat(sent.path("items").get(0).path("unidad_de_medida").asString()).isEqualTo("NIU");
    }

    @Test
    void distingueRechazoFallaTecnicaYPendienteDeSunat() {
        responder = body -> new Object[]{400, "{\"errors\": \"El número de documento del cliente es inválido\", \"codigo\": 20}"};
        assertThat(provider.issue(context(), boleta()).outcome()).isEqualTo(ProviderResult.Outcome.REJECTED);

        responder = body -> new Object[]{502, "<html>bad gateway</html>"};
        assertThatThrownBy(() -> provider.issue(context(), boleta())).isInstanceOf(ProviderException.class);

        responder = body -> new Object[]{200, "{\"aceptada_por_sunat\": false, \"sunat_responsecode\": null}"};
        assertThat(provider.issue(context(), boleta()).outcome()).isEqualTo(ProviderResult.Outcome.PROCESSING);
    }

    @Test
    void siYaExisteConsultaSuEstadoEnVezDeEmitirDeNuevo() {
        responder = body -> "generar_comprobante".equals(body.path("operacion").asString())
                ? new Object[]{400, "{\"errors\": \"Este documento ya existe en NubeFacT\", \"codigo\": 23}"}
                : new Object[]{200, "{\"aceptada_por_sunat\": true, \"sunat_responsecode\": \"0\"}"};
        ProviderResult result = provider.issue(context(), boleta());
        assertThat(result.outcome()).isEqualTo(ProviderResult.Outcome.ACCEPTED);
        assertThat(requests).extracting(r -> r.path("operacion").asString())
                .containsExactly("generar_comprobante", "consultar_comprobante");
    }

    @Test
    void laPruebaDeConexionDetectaUnTokenInvalido() {
        responder = body -> new Object[]{401, "{\"errors\": \"Token no autorizado\"}"};
        assertThat(provider.healthCheck(context()).ok()).isFalse();
        responder = body -> new Object[]{400, "{\"errors\": \"El documento no existe\", \"codigo\": 24}"};
        assertThat(provider.healthCheck(context()).ok()).isTrue();
    }

    @Test
    void soloLlamaAHostsPermitidosPorHttps() {
        NubefactBillingProvider strict = new NubefactBillingProvider(json, "nubefact.com", false, 3);
        assertThat(strict.isAllowedEndpoint("https://api.nubefact.com/api/v1/abc")).isTrue();
        assertThat(strict.isAllowedEndpoint("http://api.nubefact.com/api/v1/abc")).isFalse();
        assertThat(strict.isAllowedEndpoint("https://nubefact.com.evil.test/api")).isFalse();
        assertThat(strict.isAllowedEndpoint("https://169.254.169.254/latest/meta-data")).isFalse();
        assertThat(strict.isAllowedEndpoint("http://localhost:8080/admin")).isFalse();
        assertThat(strict.download(new ProviderContext(endpoint, "t", "20600000005"), "http://127.0.0.1:9/secret")).isNull();
    }

    @Test
    void laNotaDeCreditoIndicaElDocumentoQueModifica() {
        responder = body -> new Object[]{200, "{\"aceptada_por_sunat\": true, \"sunat_responsecode\": \"0\"}"};
        IssueRequest boleta = boleta();
        IssueRequest note = new IssueRequest(DocumentType.NOTA_CREDITO, "BC01", 1, boleta.issueDate(), boleta.issuerRuc(),
                boleta.issuerName(), boleta.customerDocumentType(), boleta.customerDocumentNumber(), boleta.customerName(),
                null, null, TaxAffectation.GRAVADO, boleta.subtotal(), boleta.tax(), boleta.total(), "PEN", boleta.items(),
                new IssueRequest.Related(DocumentType.BOLETA, "B001", 7, CreditNoteReason.ANULACION, "Pedido cancelado"));
        provider.issue(context(), note);
        JsonNode sent = requests.get(0);
        assertThat(sent.path("tipo_de_comprobante").asInt()).isEqualTo(3);
        assertThat(sent.path("documento_que_se_modifica_tipo").asInt()).isEqualTo(2);
        assertThat(sent.path("documento_que_se_modifica_serie").asString()).isEqualTo("B001");
        assertThat(sent.path("documento_que_se_modifica_numero").asLong()).isEqualTo(7);
        assertThat(sent.path("tipo_de_nota_de_credito").asInt()).isEqualTo(1);
    }

    private ProviderContext context() {
        return new ProviderContext(endpoint, "token-de-prueba-1234567890", "20600000005");
    }

    private static IssueRequest boleta() {
        return new IssueRequest(DocumentType.BOLETA, "B001", 7, LocalDate.of(2026, 9, 26), "20600000005", "BODEGA SEGURA SAC",
                IdentityDocumentType.DNI, "45678912", "ANA PÉREZ", null, null, TaxAffectation.GRAVADO,
                new BigDecimal("50.85"), new BigDecimal("9.15"), new BigDecimal("60.00"), "PEN",
                List.of(new IssueRequest.Item("A1", "Pollo a la brasa", new BigDecimal("2"), new BigDecimal("25.4237288136"),
                        new BigDecimal("30.0000000000"), new BigDecimal("50.85"), new BigDecimal("9.15"), new BigDecimal("60.00"))),
                null);
    }
}
