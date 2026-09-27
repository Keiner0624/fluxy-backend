package com.fluxyBackend.service;

import com.fluxyBackend.entity.OrderStatus;

/** Un pedido cambió de estado desde el panel. */
public record OrderStatusChangedEvent(Long orderId, Long companyId, OrderStatus status) {
}
