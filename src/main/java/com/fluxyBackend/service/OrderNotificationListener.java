package com.fluxyBackend.service;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Order;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.repository.OrderRepository;
import com.fluxyBackend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderNotificationListener {

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final WhatsAppService whatsAppService;
    private final PushNotificationService pushNotificationService;

    @Async
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCreated(OrderCreatedEvent event) {
        try {
            Order order = orderRepository.findDetailedById(event.orderId())
                    .orElseThrow(() -> new IllegalStateException("Pedido no encontrado"));
            User owner = userRepository.findFirstByCompanyId(event.companyId()).orElse(null);
            if (owner == null) {
                log.warn("No se encontró propietario para notificar el pedido #{}", event.orderId());
                return;
            }

            emailService.sendOrderNotification(owner.getEmail(), owner.getFullName(), order);
            pushNotificationService.notifyNewOrder(owner.getEmail(), "#" + order.getId());

            Company company = order.getCompany();
            if (company != null
                    && (company.getPlan() == Company.Plan.PRO
                    || company.getPlan() == Company.Plan.BUSINESS)
                    && company.getPhone() != null
                    && !company.getPhone().isBlank()) {
                whatsAppService.sendWhatsAppNotification(company.getPhone(), order, company);
            }
        } catch (Exception e) {
            log.error("Error notificando el pedido #{}", event.orderId(), e);
        }
    }
}
