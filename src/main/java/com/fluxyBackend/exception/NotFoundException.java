package com.fluxyBackend.exception;

/**
 * Se lanza cuando el recurso pedido no existe.
 *
 * Antes estos casos usaban RuntimeException, que el manejador global no
 * distingue de un fallo interno y terminaba devolviendo 500. Pedir una tienda
 * inexistente no es un error del servidor: es un 404.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
