package com.fluxyBackend.billing;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * Crea la suscripción de los negocios que tenían un plan pago antes de este módulo
 * (solo Company.plan y planExpiresAt). Idempotente: no toca a quien ya tiene suscripción.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionBackfill implements ApplicationRunner {

    private final CompanyRepository companies;
    private final SubscriptionRepository subscriptions;
    private final SubscriptionEventRepository events;
    private final TransactionTemplate transactions;

    @Override
    public void run(ApplicationArguments args) {
        Integer created = transactions.execute(status -> {
            int count = 0;
            LocalDateTime now = LocalDateTime.now();
            for (Company company : companies.findByPlanNot(Company.Plan.FREE)) {
                if (subscriptions.findByCompanyId(company.getId()).isPresent()) continue;
                Subscription s = new Subscription(company.getId());
                s.setPlan(company.getPlan());
                LocalDateTime end = company.getPlanExpiresAt();
                if (end == null) {
                    // Datos viejos sin vencimiento: se les da un periodo para no cortarles el plan sin aviso.
                    end = now.plusMonths(1);
                    company.setPlanExpiresAt(end);
                }
                s.setStatus(end.isAfter(now) ? Subscription.Status.ACTIVE : Subscription.Status.EXPIRED);
                s.setCurrentPeriodStart(company.getPlanActivatedAt() != null ? company.getPlanActivatedAt() : now);
                s.setCurrentPeriodEnd(end);
                subscriptions.save(s);
                events.save(new SubscriptionEvent(company.getId(), SubscriptionEvent.Type.SUBSCRIPTION_STARTED, null,
                        company.getPlan(), s.getCurrentPeriodStart(), null, "Sistema", "Migración del plan existente"));
                if (!end.isAfter(now)) {
                    // Ya vencido: queda en Free (antes lo cerraba la tarea diaria).
                    company.setPlan(Company.Plan.FREE);
                    company.setPlanActivatedAt(null);
                    company.setPlanExpiresAt(null);
                }
                count++;
            }
            return count;
        });
        if (created != null && created > 0) log.info("Suscripciones creadas para {} negocio(s) con plan existente", created);
    }
}
