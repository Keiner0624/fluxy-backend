package com.fluxyBackend.security;

/** 429 con Retry-After. */
public class RateLimitedException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitedException(long retryAfterSeconds) {
        this(retryAfterSeconds, "Demasiadas solicitudes. Probá de nuevo en "
                + (retryAfterSeconds < 90 ? retryAfterSeconds + " segundos." : ((retryAfterSeconds / 60) + 1) + " minutos."));
    }

    public RateLimitedException(long retryAfterSeconds, String message) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
