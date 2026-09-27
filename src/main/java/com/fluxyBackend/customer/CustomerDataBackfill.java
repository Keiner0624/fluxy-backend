package com.fluxyBackend.customer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Completa, una sola vez por fila, lo que los clientes anteriores al CRM no tenían: el origen
 * (tienda online si alguna vez compró por la tienda, punto de venta si solo por el panel, manual
 * si no tiene pedidos) y su línea de tiempo reconstruida desde los pedidos. Todo en SQL por
 * conjuntos e idempotente (NOT EXISTS): correrlo de nuevo no duplica nada.
 */
@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class CustomerDataBackfill implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int sources = jdbc.update("""
                    UPDATE customers SET source = 'ONLINE_STORE' WHERE source IS NULL
                      AND EXISTS (SELECT 1 FROM orders o WHERE o.customer_id = customers.id AND o.owner_id IS NULL)""");
            sources += jdbc.update("""
                    UPDATE customers SET source = 'POS' WHERE source IS NULL
                      AND EXISTS (SELECT 1 FROM orders o WHERE o.customer_id = customers.id)""");
            sources += jdbc.update("UPDATE customers SET source = 'MANUAL' WHERE source IS NULL");

            int activity = jdbc.update("""
                    INSERT INTO customer_activities (company_id, customer_id, type, description, created_at)
                    SELECT c.company_id, c.id, 'CUSTOMER_CREATED', 'Cliente registrado', c.created_at FROM customers c
                    WHERE c.created_at IS NOT NULL AND NOT EXISTS (
                      SELECT 1 FROM customer_activities a WHERE a.customer_id = c.id AND a.type = 'CUSTOMER_CREATED')""");
            activity += jdbc.update("""
                    INSERT INTO customer_activities (company_id, customer_id, type, reference_id, description, amount, created_at)
                    SELECT o.company_id, o.customer_id, 'ORDER_CREATED', o.id, CONCAT('Pedido #', CAST(o.id AS VARCHAR(20))),
                           o.total, o.created_at
                    FROM orders o
                    WHERE o.customer_id IS NOT NULL AND o.company_id IS NOT NULL AND o.created_at IS NOT NULL AND NOT EXISTS (
                      SELECT 1 FROM customer_activities a
                      WHERE a.customer_id = o.customer_id AND a.type = 'ORDER_CREATED' AND a.reference_id = o.id)""");
            activity += jdbc.update("""
                    INSERT INTO customer_activities (company_id, customer_id, type, reference_id, description, amount, created_at)
                    SELECT o.company_id, o.customer_id,
                           CASE WHEN o.status = 'CANCELLED' THEN 'ORDER_CANCELLED' ELSE 'ORDER_DELIVERED' END, o.id,
                           CONCAT('Pedido #', CAST(o.id AS VARCHAR(20)),
                                  CASE WHEN o.status = 'CANCELLED' THEN ' cancelado' ELSE ' entregado' END),
                           o.total, COALESCE(o.updated_at, o.created_at)
                    FROM orders o
                    WHERE o.customer_id IS NOT NULL AND o.company_id IS NOT NULL AND o.created_at IS NOT NULL
                      AND o.status IN ('DELIVERED', 'COMPLETED', 'CANCELLED') AND NOT EXISTS (
                      SELECT 1 FROM customer_activities a
                      WHERE a.customer_id = o.customer_id AND a.reference_id = o.id
                        AND a.type IN ('ORDER_DELIVERED', 'ORDER_CANCELLED'))""");
            if (sources + activity > 0) {
                log.info("CRM: {} origen(es) completados y {} actividad(es) reconstruidas", sources, activity);
            }
        } catch (RuntimeException e) {
            // No debe impedir que arranque la aplicación: se reintenta en el próximo inicio.
            log.warn("No se pudo completar los datos de clientes: {}", e.getMessage());
        }
    }
}
