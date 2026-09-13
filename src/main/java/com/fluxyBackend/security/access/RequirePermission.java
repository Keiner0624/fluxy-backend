package com.fluxyBackend.security.access;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Exige permisos a quien llama al endpoint. Lo valida PermissionInterceptor
 * antes de entrar al controlador; el frontend solo oculta botones, no protege.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {
    /** Permisos exigidos: todos, salvo que any sea true. */
    Permission[] value();

    /** Basta con uno de los permisos. */
    boolean any() default false;
}
