package com.fluxyBackend.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Frena la fuerza bruta contra el login.
 *
 * Antes se podían probar contraseñas sin límite: veinte intentos fallidos
 * seguidos devolvían veinte 401 sin ninguna traba.
 *
 * Se cuenta por cuenta y por IP a la vez, porque cada límite cubre un hueco del
 * otro: el de IP no sirve si el atacante rota direcciones, y el de cuenta no
 * sirve contra credential stuffing, que prueba una contraseña en muchas cuentas.
 *
 * Los contadores viven en memoria. Alcanza para una sola instancia, que es lo
 * que hay hoy en Render. Con varias instancias haría falta llevarlos a Redis,
 * porque cada una contaría por su cuenta.
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    /** Un usuario que se equivoca de contraseña rara vez pasa de cinco. */
    private static final int MAX_POR_CUENTA = 5;

    /** Más alto: detrás de una IP puede haber una oficina entera. */
    private static final int MAX_POR_IP = 20;

    private static final Duration VENTANA = Duration.ofMinutes(15);
    private static final Duration BLOQUEO = Duration.ofMinutes(15);

    private final Map<String, Contador> porCuenta = new ConcurrentHashMap<>();
    private final Map<String, Contador> porIp = new ConcurrentHashMap<>();

    private static final class Contador {
        int fallos;
        Instant inicioVentana = Instant.now();
        Instant bloqueadoHasta;
    }

    /**
     * @return segundos que faltan para poder reintentar, o 0 si no está bloqueado.
     */
    public long segundosDeBloqueo(String email, String ip) {
        long porCuentaSeg = restante(porCuenta.get(clave(email)));
        long porIpSeg = restante(porIp.get(clave(ip)));
        return Math.max(porCuentaSeg, porIpSeg);
    }

    public void registrarFallo(String email, String ip) {
        sumar(porCuenta, clave(email), MAX_POR_CUENTA, "cuenta");
        sumar(porIp, clave(ip), MAX_POR_IP, "IP");
    }

    /** Un login correcto limpia el contador de esa cuenta y esa IP. */
    public void registrarExito(String email, String ip) {
        porCuenta.remove(clave(email));
        porIp.remove(clave(ip));
    }

    private void sumar(Map<String, Contador> mapa, String clave, int maximo, String tipo) {
        if (clave == null) return;

        mapa.compute(clave, (k, actual) -> {
            Instant ahora = Instant.now();
            Contador c = actual;

            // Ventana vencida o bloqueo cumplido: se empieza de cero.
            if (c == null
                    || (c.bloqueadoHasta != null && ahora.isAfter(c.bloqueadoHasta))
                    || ahora.isAfter(c.inicioVentana.plus(VENTANA))) {
                c = new Contador();
            }

            c.fallos++;
            if (c.fallos >= maximo && c.bloqueadoHasta == null) {
                c.bloqueadoHasta = ahora.plus(BLOQUEO);
                log.warn("Login bloqueado por {} tras {} intentos fallidos", tipo, c.fallos);
            }
            return c;
        });
    }

    private long restante(Contador c) {
        if (c == null || c.bloqueadoHasta == null) return 0;
        long segundos = Duration.between(Instant.now(), c.bloqueadoHasta).getSeconds();
        return Math.max(segundos, 0);
    }

    private String clave(String valor) {
        return valor == null || valor.isBlank() ? null : valor.trim().toLowerCase();
    }

    /** Sin esto los mapas crecerían con cada IP que alguna vez falló. */
    @Scheduled(fixedDelay = 10 * 60 * 1000L)
    void limpiarVencidos() {
        Instant ahora = Instant.now();
        purgar(porCuenta, ahora);
        purgar(porIp, ahora);
    }

    private void purgar(Map<String, Contador> mapa, Instant ahora) {
        mapa.values().removeIf(c -> {
            boolean bloqueoVencido = c.bloqueadoHasta != null && ahora.isAfter(c.bloqueadoHasta);
            boolean ventanaVencida = c.bloqueadoHasta == null
                    && ahora.isAfter(c.inicioVentana.plus(VENTANA));
            return bloqueoVencido || ventanaVencida;
        });
    }
}
