package com.fluxyBackend.config;

import com.fluxyBackend.repository.OrderRepository;
import com.fluxyBackend.service.CustomerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

/**
 * Ajustes de esquema y datos que ddl-auto=update no hace. Todo es idempotente:
 * corre en cada arranque y no cambia nada si ya se aplicó.
 *
 * Mientras no haya Flyway, este es el lugar para migraciones de datos.
 */
@Component
@Order(0)
@RequiredArgsConstructor
@Slf4j
public class SchemaUpgrade implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final OrderRepository orderRepository;
    private final CustomerService customerService;

    @Override
    public void run(ApplicationArguments args) {
        if (isPostgres()) dropEnumCheckConstraints();

        // Nuevo flujo de pedidos: COMPLETED pasó a llamarse DELIVERED.
        execute("UPDATE orders SET status = 'DELIVERED' WHERE status = 'COMPLETED'");

        // Columnas agregadas sobre tablas con datos: se crean nulas.
        execute("UPDATE products SET status = 'ACTIVE' WHERE status IS NULL");
        execute("UPDATE products SET min_stock = 5 WHERE min_stock IS NULL");
        execute("UPDATE products SET created_at = CURRENT_TIMESTAMP WHERE created_at IS NULL");
        execute("UPDATE categories SET active = TRUE WHERE active IS NULL");
        execute("UPDATE categories SET sort_order = id WHERE sort_order IS NULL");

        execute("CREATE INDEX IF NOT EXISTS idx_orders_company_created ON orders (company_id, created_at)");
        execute("CREATE INDEX IF NOT EXISTS idx_orders_customer ON orders (customer_id)");
        execute("CREATE INDEX IF NOT EXISTS idx_products_company ON products (company_id)");

        // Dueños registrados antes de que existieran los memberships.
        execute("""
                INSERT INTO membership (user_id, company_id, role, status, joined_at)
                SELECT u.id, u.company_id, 'OWNER', 'ACTIVE', CURRENT_TIMESTAMP
                FROM users u
                WHERE u.role = 'BUSINESS_OWNER' AND u.company_id IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM membership m WHERE m.user_id = u.id AND m.company_id = u.company_id)
                """);

        linkExistingOrdersToCustomers();
        securityAndLifecycle();

        // Stock que ya existía antes del inventario: queda como punto de partida del historial.
        execute("""
                INSERT INTO inventory_movements
                    (company_id, product_id, product_name, type, quantity, stock_before, stock_after, reason, created_by, created_at)
                SELECT p.company_id, p.id, p.name, 'INITIAL', p.stock, 0, p.stock,
                       'Stock registrado al activar el inventario', 'Sistema', CURRENT_TIMESTAMP
                FROM products p
                WHERE p.company_id IS NOT NULL
                  AND NOT EXISTS (SELECT 1 FROM inventory_movements m WHERE m.product_id = p.id)
                """);
    }

    /** Cuentas y negocios anteriores al documento de seguridad. */
    private void securityAndLifecycle() {
        execute("UPDATE users SET status = 'ACTIVE' WHERE status IS NULL");
        execute("UPDATE users SET password_enabled = TRUE WHERE password_enabled IS NULL");
        execute("UPDATE users SET created_at = CURRENT_TIMESTAMP WHERE created_at IS NULL");
        execute("UPDATE company SET status = 'ACTIVE' WHERE status IS NULL");
        // El reloj de inactividad arranca en la última venta conocida, o hoy.
        execute("""
                UPDATE company SET last_business_activity_at = COALESCE(
                    (SELECT MAX(o.created_at) FROM orders o WHERE o.company_id = company.id), CURRENT_TIMESTAMP)
                WHERE last_business_activity_at IS NULL
                """);
        execute("CREATE INDEX IF NOT EXISTS idx_users_company ON users (company_id)");
        execute("CREATE INDEX IF NOT EXISTS idx_company_status ON company (status)");
        execute("CREATE INDEX IF NOT EXISTS idx_customers_company ON customers (company_id)");
        // Un dominio propio pertenece a una sola tienda (los null no chocan entre sí).
        execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_company_custom_domain ON company (custom_domain)");
        // Dominios conectados antes de guardar su estado: se revisan en la próxima pasada.
        execute("UPDATE company SET custom_domain_status = 'PENDING_DNS', custom_domain_added_at = CURRENT_TIMESTAMP "
                + "WHERE custom_domain IS NOT NULL AND custom_domain_status IS NULL");

        // El stock nunca queda negativo: además de la validación, lo garantiza la base.
        if (isPostgres()) {
            Integer negatives = jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE stock < 0", Integer.class);
            Integer existing = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pg_constraint WHERE conname = 'chk_products_stock_non_negative'", Integer.class);
            if (negatives != null && negatives == 0 && existing != null && existing == 0) {
                execute("ALTER TABLE products ADD CONSTRAINT chk_products_stock_non_negative CHECK (stock >= 0)");
            } else if (negatives != null && negatives > 0) {
                log.warn("Hay {} productos con stock negativo: no se agregó la restricción de stock", negatives);
            }
        }
    }

    private void linkExistingOrdersToCustomers() {
        List<Long> companies;
        try {
            companies = orderRepository.findCompanyIdsWithOrdersWithoutCustomer();
        } catch (RuntimeException e) {
            log.warn("No se pudieron buscar pedidos sin cliente: {}", e.getMessage());
            return;
        }
        for (Long companyId : companies) {
            try {
                Integer linked = transactions.execute(status -> customerService.linkOrdersWithoutCustomer(companyId));
                log.info("Empresa {}: {} pedidos asociados a clientes", companyId, linked);
            } catch (RuntimeException e) {
                log.warn("No se pudieron asociar los pedidos de la empresa {} a clientes: {}", companyId, e.getMessage());
            }
        }
        // Un cliente creado ahora a partir de pedidos viejos es cliente desde su primer pedido.
        execute("""
                UPDATE customers SET created_at = (SELECT MIN(o.created_at) FROM orders o WHERE o.customer_id = customers.id)
                WHERE EXISTS (SELECT 1 FROM orders o WHERE o.customer_id = customers.id AND o.created_at < customers.created_at)
                """);
    }

    /**
     * Hibernate crea un CHECK con los valores de cada enum, pero ddl-auto=update
     * no lo actualiza cuando el enum crece: los estados nuevos del pedido
     * fallarían al guardarse. Los valores ya los valida la aplicación.
     */
    private void dropEnumCheckConstraints() {
        List<Map<String, Object>> constraints = jdbc.queryForList("""
                SELECT c.conrelid::regclass::text AS table_name, c.conname AS constraint_name
                FROM pg_constraint c
                JOIN pg_namespace n ON n.oid = c.connamespace
                WHERE c.contype = 'c'
                  AND n.nspname = current_schema()
                  AND pg_get_constraintdef(c.oid) LIKE '%= ANY (%ARRAY[%'
                """);
        // Postgres lo escribe como "= ANY ((ARRAY['A'::character varying, ...])::text[])".
        for (Map<String, Object> row : constraints) {
            String table = String.valueOf(row.get("table_name"));
            String name = String.valueOf(row.get("constraint_name")).replace("\"", "\"\"");
            if (execute("ALTER TABLE " + table + " DROP CONSTRAINT IF EXISTS \"" + name + "\"")) {
                log.info("Restricción de enum eliminada: {}.{}", table, name);
            }
        }
    }

    private boolean isPostgres() {
        try {
            String product = jdbc.execute((ConnectionCallback<String>) c -> c.getMetaData().getDatabaseProductName());
            return product != null && product.toLowerCase().contains("postgres");
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean execute(String sql) {
        try {
            jdbc.execute(sql);
            return true;
        } catch (RuntimeException e) {
            log.warn("Actualización de esquema omitida ({}): {}", firstLine(sql), e.getMessage());
            return false;
        }
    }

    private static String firstLine(String sql) {
        String trimmed = sql.strip();
        int newline = trimmed.indexOf('\n');
        return newline < 0 ? trimmed : trimmed.substring(0, newline);
    }
}
