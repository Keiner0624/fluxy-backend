package com.fluxyBackend.service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Corta las llamadas a un proveedor que está fallando.
 *
 * Tras varios fallos seguidos deja de llamarlo por un rato: así un proveedor
 * caído no hace esperar el tiempo límite completo a cada petición. Pasado ese
 * rato deja pasar una llamada de prueba.
 */
public class CircuitBreaker {

    private final String name;
    private final int failureThreshold;
    private final Duration openFor;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong openedAt = new AtomicLong(0);

    public CircuitBreaker(String name, int failureThreshold, Duration openFor) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.openFor = openFor;
    }

    public boolean allowRequest() {
        long opened = openedAt.get();
        if (opened == 0) return true;
        // Medio abierto: pasado el tiempo, una sola llamada de prueba.
        return System.currentTimeMillis() - opened >= openFor.toMillis()
                && openedAt.compareAndSet(opened, System.currentTimeMillis());
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
        openedAt.set(0);
    }

    public void recordFailure() {
        if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
            openedAt.compareAndSet(0, System.currentTimeMillis());
        }
    }

    public boolean isOpen() {
        return openedAt.get() != 0;
    }

    public String name() {
        return name;
    }
}
