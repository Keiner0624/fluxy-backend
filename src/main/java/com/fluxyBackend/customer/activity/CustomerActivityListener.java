package com.fluxyBackend.customer.activity;

import com.fluxyBackend.entity.Order;
import com.fluxyBackend.entity.OrderStatus;
import com.fluxyBackend.repository.OrderPaymentRepository;
import com.fluxyBackend.repository.OrderRepository;
import com.fluxyBackend.service.OrderPaymentService;
import com.fluxyBackend.service.OrderStatusChangedEvent;
import com.fluxyBackend.service.PaymentApprovedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Locale;

/**
 * Actividad que nace de eventos de pedidos y pagos. BEFORE_COMMIT: corre dentro de la misma
 * transacción, así la línea de tiempo nunca registra algo que no se guardó.
 */
@Component
@RequiredArgsConstructor
public class CustomerActivityListener {

    private final CustomerActivityService activity;
    private final OrderRepository orders;
    private final OrderPaymentRepository payments;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onPaymentApproved(PaymentApprovedEvent event) {
        Order order = orders.findByIdAndCompanyId(event.orderId(), event.companyId()).orElse(null);
        if (order == null || order.getCustomerId() == null) return;
        String status = OrderPaymentService.paymentStatus(order.getTotal() == null ? 0 : order.getTotal(),
                payments.findByOrderIdOrderByCreatedAtAscIdAsc(order.getId()));
        if (!"PAID".equals(status)) return;
        activity.recordOnce(event.companyId(), order.getCustomerId(), CustomerActivityType.ORDER_PAID, order.getId(),
                "Pedido #" + order.getId() + " pagado", order.getTotal(), null);
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onOrderStatusChanged(OrderStatusChangedEvent event) {
        if (event.status() != OrderStatus.DELIVERED && event.status() != OrderStatus.CANCELLED) return;
        Order order = orders.findByIdAndCompanyId(event.orderId(), event.companyId()).orElse(null);
        if (order == null || order.getCustomerId() == null) return;
        boolean delivered = event.status() == OrderStatus.DELIVERED;
        String text = "Pedido #" + order.getId() + (delivered ? " entregado"
                : " cancelado" + (order.getCancelReason() != null ? ": " + order.getCancelReason().toLowerCase(Locale.ROOT) : ""));
        activity.recordOnce(event.companyId(), order.getCustomerId(),
                delivered ? CustomerActivityType.ORDER_DELIVERED : CustomerActivityType.ORDER_CANCELLED,
                order.getId(), text, order.getTotal(), null);
    }
}
