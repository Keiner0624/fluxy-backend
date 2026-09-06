package com.fluxyBackend.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fija el comportamiento del freno a la fuerza bruta: cuántos fallos se toleran,
 * que un acierto limpie el contador y que los dos límites (cuenta e IP) actúen
 * de forma independiente.
 */
class LoginAttemptServiceTest {

    private LoginAttemptService service;

    @BeforeEach
    void setUp() {
        service = new LoginAttemptService();
    }

    @Test
    void noBloqueaAntesDelLimite() {
        for (int i = 0; i < 4; i++) {
            service.registrarFallo("duenio@fluxy.test", "1.1.1.1");
        }
        assertThat(service.segundosDeBloqueo("duenio@fluxy.test", "1.1.1.1")).isZero();
    }

    @Test
    void bloqueaLaCuentaAlQuintoFallo() {
        for (int i = 0; i < 5; i++) {
            service.registrarFallo("duenio@fluxy.test", "1.1.1.1");
        }
        assertThat(service.segundosDeBloqueo("duenio@fluxy.test", "1.1.1.1")).isPositive();
    }

    @Test
    void elBloqueoDeUnaCuentaNoAfectaAOtra() {
        for (int i = 0; i < 5; i++) {
            service.registrarFallo("victima@fluxy.test", "1.1.1.1");
        }
        // Otra cuenta desde otra IP sigue pudiendo entrar.
        assertThat(service.segundosDeBloqueo("otro@fluxy.test", "2.2.2.2")).isZero();
    }

    @Test
    void bloqueaPorIpAunqueRoteLasCuentas() {
        // Credential stuffing: una contraseña probada en muchas cuentas distintas.
        for (int i = 0; i < 20; i++) {
            service.registrarFallo("victima" + i + "@fluxy.test", "9.9.9.9");
        }
        // Ninguna cuenta llegó a cinco fallos, pero la IP sí al límite.
        assertThat(service.segundosDeBloqueo("recien-llegado@fluxy.test", "9.9.9.9")).isPositive();
    }

    @Test
    void unLoginCorrectoLimpiaElContador() {
        for (int i = 0; i < 4; i++) {
            service.registrarFallo("duenio@fluxy.test", "1.1.1.1");
        }
        service.registrarExito("duenio@fluxy.test", "1.1.1.1");

        // Vuelve a tener los cinco intentos disponibles.
        for (int i = 0; i < 4; i++) {
            service.registrarFallo("duenio@fluxy.test", "1.1.1.1");
        }
        assertThat(service.segundosDeBloqueo("duenio@fluxy.test", "1.1.1.1")).isZero();
    }

    @Test
    void elCorreoNoDistingueMayusculas() {
        for (int i = 0; i < 5; i++) {
            service.registrarFallo("Duenio@Fluxy.Test", "1.1.1.1");
        }
        assertThat(service.segundosDeBloqueo("duenio@fluxy.test", "3.3.3.3")).isPositive();
    }
}
