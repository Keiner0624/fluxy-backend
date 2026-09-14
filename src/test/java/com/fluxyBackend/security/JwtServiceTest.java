package com.fluxyBackend.security;

import com.fluxyBackend.entity.User;
import io.jsonwebtoken.ExpiredJwtException;
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

    private static User usuario(String email) {
        User user = new User();
        user.setId(7L);
        user.setEmail(email);
        return user;
    }

    @Test
    void elAccessTokenLlevaUsuarioYSesion() {
        JwtService service = servicioCon(CLAVE_VALIDA);
        service.init();

        String token = service.generateAccessToken(usuario("duenio@fluxy.test"), "sesion-1");
        JwtService.TokenClaims claims = service.parse(token);

        assertThat(claims.subject()).isEqualTo("duenio@fluxy.test");
        assertThat(claims.userId()).isEqualTo(7L);
        assertThat(claims.sessionId()).isEqualTo("sesion-1");
        assertThat(claims.type()).isEqualTo(JwtService.TYPE_ACCESS);
    }

    @Test
    void elAccessTokenDuraQuinceMinutos() {
        assertThat(servicioCon(CLAVE_VALIDA).accessTtl()).hasMinutes(15);
    }

    @Test
    void unTokenVencidoNoSeAcepta() {
        JwtService service = servicioCon(CLAVE_VALIDA);
        ReflectionTestUtils.setField(service, "accessTtlMinutes", -1L);
        service.init();
        String vencido = service.generateAccessToken(usuario("duenio@fluxy.test"), "sesion-1");

        assertThatThrownBy(() -> service.parse(vencido)).isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void unTokenFirmadoConOtraClaveNoSeAcepta() {
        JwtService emisor = servicioCon(Base64.getEncoder().encodeToString("otra-clave-de-32-bytes-exactos!!".getBytes()));
        emisor.init();
        String tokenAjeno = emisor.generateAccessToken(usuario("intruso@fluxy.test"), "sesion-x");

        JwtService receptor = servicioCon(CLAVE_VALIDA);
        receptor.init();

        assertThatThrownBy(() -> receptor.parse(tokenAjeno))
                .isInstanceOf(Exception.class);
    }
}
