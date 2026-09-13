package com.fluxyBackend.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderStatusTest {

    @Test
    void unPedidoAvanzaYPuedeSaltarEtapas() {
        assertThat(OrderStatus.PENDING.canMoveTo(OrderStatus.CONFIRMED)).isTrue();
        // Venta en mostrador: de pendiente a entregado sin pasar por las etapas intermedias.
        assertThat(OrderStatus.PENDING.canMoveTo(OrderStatus.DELIVERED)).isTrue();
        assertThat(OrderStatus.READY.canMoveTo(OrderStatus.SHIPPED)).isTrue();
    }

    @Test
    void unPedidoNuncaRetrocede() {
        assertThat(OrderStatus.SHIPPED.canMoveTo(OrderStatus.PREPARING)).isFalse();
        assertThat(OrderStatus.CONFIRMED.canMoveTo(OrderStatus.PENDING)).isFalse();
    }

    @Test
    void seCancelaMientrasNoSeEntregue() {
        assertThat(OrderStatus.SHIPPED.canMoveTo(OrderStatus.CANCELLED)).isTrue();
        assertThat(OrderStatus.DELIVERED.canMoveTo(OrderStatus.CANCELLED)).isFalse();
        assertThat(OrderStatus.CANCELLED.nextStatuses()).isEmpty();
    }

    @Test
    void ventaEsDesdeConfirmadoYElEstadoViejoCuenta() {
        assertThat(OrderStatus.PENDING.isSale()).isFalse();
        assertThat(OrderStatus.CANCELLED.isSale()).isFalse();
        assertThat(OrderStatus.CONFIRMED.isSale()).isTrue();
        assertThat(OrderStatus.COMPLETED.isSale()).isTrue();
        assertThat(OrderStatus.COMPLETED.isFinal()).isTrue();
    }
}
