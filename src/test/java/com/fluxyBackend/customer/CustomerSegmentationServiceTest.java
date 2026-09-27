package com.fluxyBackend.customer;

import com.fluxyBackend.customer.segmentation.CustomerSegmentType;
import com.fluxyBackend.customer.segmentation.CustomerSegmentationService;
import com.fluxyBackend.customer.segmentation.CustomerSegmentationService.Stats;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static com.fluxyBackend.customer.segmentation.CustomerSegmentType.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Reglas de segmentos con los umbrales por defecto (30 días, 2/3 compras, S/ 300). */
class CustomerSegmentationServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 27, 12, 0);

    private final CustomerSegmentationService service = new CustomerSegmentationService(null, 30, 2, 30, 3, 30, 300);

    @Test
    void sinComprasNoTieneSegmentos() {
        assertThat(service.classify(null, NOW)).isEmpty();
        assertThat(service.classify(new Stats(0, 0, null, null), NOW)).isEmpty();
    }

    @Test
    void primeraCompraRecienteEsNuevo() {
        assertThat(service.classify(stats(1, 50, 5, 5), NOW)).containsExactly(NEW);
    }

    @Test
    void dosComprasRecientesEsRecurrenteYTresEsFrecuente() {
        assertThat(service.classify(stats(2, 80, 60, 3), NOW)).containsExactly(RECURRING);
        assertThat(service.classify(stats(3, 80, 60, 3), NOW)).containsExactlyInAnyOrder(RECURRING, FREQUENT);
    }

    @Test
    void treintaDiasSinComprarEsInactivoYYaNoRecurrente() {
        assertThat(service.classify(stats(3, 120, 200, 30), NOW)).containsExactlyInAnyOrder(FREQUENT, INACTIVE);
        assertThat(service.classify(stats(1, 40, 29, 29), NOW)).containsExactly(NEW);
    }

    @Test
    void altoValorPorMontoAcumulado() {
        assertThat(service.classify(stats(1, 300, 90, 90), NOW)).containsExactlyInAnyOrder(INACTIVE, HIGH_VALUE);
        assertThat(service.classify(stats(1, 299.99, 90, 90), NOW)).doesNotContain(HIGH_VALUE);
    }

    @Test
    void lasReglasSeExplicanConLosUmbralesConfigurados() {
        CustomerSegmentationService custom = new CustomerSegmentationService(null, 15, 2, 30, 5, 45, 1000);
        assertThat(custom.rule(CustomerSegmentType.INACTIVE)).contains("45 días");
        assertThat(custom.rule(CustomerSegmentType.HIGH_VALUE)).contains("1000");
        assertThat(custom.classify(stats(4, 500, 100, 40), NOW)).isEmpty();
    }

    private static Stats stats(long orders, double spent, int firstDaysAgo, int lastDaysAgo) {
        return new Stats(orders, spent, NOW.minusDays(firstDaysAgo), NOW.minusDays(lastDaysAgo));
    }
}
