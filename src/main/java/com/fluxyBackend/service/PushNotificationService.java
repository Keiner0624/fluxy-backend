package com.fluxyBackend.service;

import com.fluxyBackend.entity.PushSubscription;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.repository.PushSubscriptionRepository;
import com.fluxyBackend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PushNotificationService {

    private final PushSubscriptionRepository pushRepo;
    private final UserRepository             userRepo;

    @Value("${vapid.public.key}")
    private String vapidPublicKey;

    @Value("${vapid.private.key}")
    private String vapidPrivateKey;

    @Value("${vapid.subject}")
    private String vapidSubject;

    public void notifyNewOrder(String userEmail, String orderLabel) {
        User user = userRepo.findByEmailIgnoreCase(userEmail).orElse(null);
        if (user == null || user.getCompany() == null) return;

        List<PushSubscription> subs = pushRepo.findByUser_Company_Id(user.getCompany().getId());
        if (subs.isEmpty()) return;

        // Payload JSON construido manualmente — sin dependencia de ObjectMapper
        String payload = "{\"title\":\"🛒 Nuevo pedido\",\"body\":\"Tienes un nuevo pedido "
                + orderLabel + "\",\"url\":\"/dashboard/orders\"}";

        try {
            PushService pushService = new PushService(vapidPublicKey, vapidPrivateKey, vapidSubject);
            for (PushSubscription sub : subs) {
                try {
                    pushService.send(new Notification(
                            sub.getEndpoint(),
                            sub.getP256dh(),
                            sub.getAuth(),
                            payload
                    ));
                    log.info("[Push] Enviado → {}", sub.getEndpoint());
                } catch (Exception e) {
                    log.warn("[Push] Fallo en {}: {}", sub.getEndpoint(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[Push] Error inicializando PushService", e);
        }
    }
}