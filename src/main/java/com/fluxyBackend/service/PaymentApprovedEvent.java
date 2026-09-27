package com.fluxyBackend.service;

/** Un cobro de un pedido quedó aprobado. Pagos cobra; los demás módulos reaccionan (facturación, etc.). */
public record PaymentApprovedEvent(Long orderId, Long companyId) {
}
