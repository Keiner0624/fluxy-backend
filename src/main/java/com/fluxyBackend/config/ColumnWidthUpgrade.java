package com.fluxyBackend.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agranda columnas que quedaron en 255 caracteres aunque la validación admite más.
 * ddl-auto=update crea columnas pero nunca las agranda: sin esto, una descripción o dirección
 * larga pasaba la validación y la base la rechazaba (respuesta 409).
 * Solo en PostgreSQL, idempotente: no toca columnas que ya tienen el ancho necesario.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class ColumnWidthUpgrade implements ApplicationRunner {

    record Target(String table, String column, int length) {}

    static final List<Target> TARGETS = List.of(
            new Target("company", "description", 2000),
            new Target("company", "address", 300),
            new Target("company", "logo_url", 2048),
            new Target("orders", "customer_address", 300));

    private final JdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        String product = jdbc.execute((java.sql.Connection c) -> c.getMetaData().getDatabaseProductName());
        if (product == null || !product.toLowerCase().contains("postgres")) return;
        for (Target t : TARGETS) {
            List<Integer> current = jdbc.queryForList(
                    "SELECT character_maximum_length FROM information_schema.columns "
                            + "WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?",
                    Integer.class, t.table(), t.column());
            if (current.isEmpty() || current.get(0) == null || current.get(0) >= t.length()) continue;
            jdbc.execute("ALTER TABLE " + t.table() + " ALTER COLUMN " + t.column() + " TYPE varchar(" + t.length() + ")");
            log.info("Columna {}.{} ampliada de {} a {} caracteres", t.table(), t.column(), current.get(0), t.length());
        }
    }
}
