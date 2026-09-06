package com.fluxyBackend.security;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * La clave de firma ya no tiene valor por defecto en application.properties.
 * Estas pruebas fijan que una clave ausente o débil impida arrancar, en vez de
 * dejar la aplicación funcionando con una firma que cualquiera puede replicar.
 */
class JwtServiceTest {

    private static final String CLAVE_VALIDA =
            Base64.getEncoder().encodeToString(new byte[32]);

    private JwtService servicioCon(String secret) {
        JwtService service = new JwtService();
        ReflectionTestUtils.setField(service, "secretKey", secret);
        ReflectionTestUtils.setField(service, "jwtExpiration", 3_600_000L);
        return service;
    }

    @Test
    void noArrancaSinClave() {
        assertThatThrownBy(() -> servicioCon("").init())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void noArrancaConClaveEnBlanco() {
        assertThatThrownBy(() -> servicioCon("   ").init())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }

    @Test
    void noArrancaConClaveDemasiadoCorta() {
        String corta = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> servicioCon(corta).init())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("demasiado corta");
    }

    @Test
    void arrancaConClaveValida() {
        assertThatCode(() -> servicioCon(CLAVE_VALIDA).init()).doesNotThrowAnyException();
    }

    @Test
    void elTokenGeneradoEsValidoParaSuDuenio() {
        JwtService service = servicioCon(CLAVE_VALIDA);
        service.init();

        String token = service.generateToken("duenio@fluxy.test");

        assertThat(service.extractUsername(token)).isEqualTo("duenio@fluxy.test");
        assertThat(service.isTokenValid(token, "duenio@fluxy.test")).isTrue();
        assertThat(service.isTokenValid(token, "otro@fluxy.test")).isFalse();
    }

    @Test
    void unTokenFirmadoConOtraClaveNoSeAcepta() {
        JwtService emisor = servicioCon(Base64.getEncoder().encodeToString("otra-clave-de-32-bytes-exactos!!".getBytes()));
        emisor.init();
        String tokenAjeno = emisor.generateToken("intruso@fluxy.test");

        JwtService receptor = servicioCon(CLAVE_VALIDA);
        receptor.init();

        assertThatThrownBy(() -> receptor.extractUsername(tokenAjeno))
                .isInstanceOf(Exception.class);
    }
}
