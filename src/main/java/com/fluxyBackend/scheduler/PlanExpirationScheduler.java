package com.fluxyBackend.scheduler;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.repository.CompanyRepository;
import com.fluxyBackend.repository.UserRepository;
import com.fluxyBackend.service.EmailService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class PlanExpirationScheduler {
    private static final Logger log = LoggerFactory.getLogger(PlanExpirationScheduler.class);
    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;

    //Correr cada dia a las 00:00
    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    public void checkExpiredPlans(){
        log.info("Checking for expired plans...");
        LocalDateTime now = LocalDateTime.now();

        // Bucar empresas con planes PRO O BUSINESS que ya expiraron
        List<Company> expired = companyRepository.findExpiredPlans(Company.Plan.FREE, now);

        for (Company company : expired) {
            String oldPlan = company.getPlan().name();
            company.setPlan(Company.Plan.FREE);
            company.setPlanActivatedAt(null);
            company.setPlanExpiresAt(null);
            companyRepository.save(company);

            log.info("Plan {} -> FREE para empresa {}", oldPlan, company.getName());

            //Enviar email de aviso al vendedor
            userRepository.findByCompanyId(company.getId())
                    .stream().findFirst().ifPresent(user -> {
                        try {
                            emailService.sendPlanExpiredEmail(user.getEmail(), user.getFullName(), oldPlan);
                        } catch (Exception e) {
                            log.error("Error enviando email de expiracion a {}: {}", user.getEmail(), e.getMessage());
                        }
                    });
        }
        log.info("Verificacion completada - {} planes vencidos.", expired.size());
    }

    //Correr cada dia a las 09:00 - avisos de vencimiento proximo
    @Scheduled(cron = "0 0 9 * * *")
    @Transactional
    public void checkExpiringPlans(){
        log.info("Checking for expiring plans...");
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime in7 = now.plusDays(7);

        //Avisar si vencen en los proximos 7 dias
        List<Company> expiring = companyRepository.findExpiringPlans(Company.Plan.FREE, now, in7);

        for (Company company : expiring) {
            LocalDateTime expires = company.getPlanExpiresAt();
            int daysLeft = (int) java.time.Duration.between(now, expires).toDays();

            if (daysLeft <= 7) {
                userRepository.findByCompanyId(company.getId())
                        .stream().findFirst().ifPresent(user -> {
                            try {
                                emailService.sendPlanExpiringEmail(user.getEmail(), user.getFullName(), company.getPlan().name(), daysLeft);
                                log.info("Aviso de vencimiento enviado a {} - {} dias", user.getEmail(), daysLeft);
                            } catch (Exception e) {
                                log.error("Error enviando email de aviso a {}: {}", user.getEmail(), e.getMessage());
                            }
                        });
            }
            log.info("Verificación de avisos completados.");

        }
    }
}
