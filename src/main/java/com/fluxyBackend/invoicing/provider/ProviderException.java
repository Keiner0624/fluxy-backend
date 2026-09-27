package com.fluxyBackend.invoicing.provider;

/** Falla técnica del proveedor (red, tiempo límite, error interno): se reintenta. */
public class ProviderException extends RuntimeException {

    private final boolean timeout;

    public ProviderException(String message, boolean timeout) {
        super(message);
        this.timeout = timeout;
    }

    public ProviderException(String message, Throwable cause) {
        super(message, cause);
        this.timeout = cause instanceof java.net.http.HttpTimeoutException;
    }

    public boolean isTimeout() {
        return timeout;
    }
}
