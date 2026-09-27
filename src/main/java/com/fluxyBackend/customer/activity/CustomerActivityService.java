package com.fluxyBackend.customer.activity;

import com.fluxyBackend.DTOs.PageResponse;
import com.fluxyBackend.service.BusinessClock;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * Registro de actividad del cliente. Se escribe en la misma transacción que el cambio que la
 * origina: si el pedido o el cambio no se guarda, la actividad tampoco.
 */
@Service
@RequiredArgsConstructor
public class CustomerActivityService {

    private final CustomerActivityRepository activities;

    public record View(Long id, String type, String description, Long referenceId, Double amount, String actor,
                       OffsetDateTime createdAt) {}

    @Transactional
    public void record(Long companyId, Long customerId, CustomerActivityType type, Long referenceId,
                       String description, Double amount, String actor) {
        if (companyId == null || customerId == null) return;
        CustomerActivity activity = new CustomerActivity();
        activity.setCompanyId(companyId);
        activity.setCustomerId(customerId);
        activity.setType(type);
        activity.setReferenceId(referenceId);
        activity.setDescription(description.length() > 300 ? description.substring(0, 300) : description);
        activity.setAmount(amount);
        activity.setActor(actor == null ? null : actor.length() > 150 ? actor.substring(0, 150) : actor);
        activity.setCreatedAt(LocalDateTime.now());
        activities.save(activity);
    }

    /** Una sola vez por referencia (por ejemplo, "pedido pagado" aunque entren dos cobros). */
    @Transactional
    public void recordOnce(Long companyId, Long customerId, CustomerActivityType type, Long referenceId,
                           String description, Double amount, String actor) {
        if (customerId == null || activities.existsByCustomerIdAndTypeAndReferenceId(customerId, type, referenceId)) return;
        record(companyId, customerId, type, referenceId, description, amount, actor);
    }

    /** Página de la línea de tiempo; siempre por empresa. */
    @Transactional(readOnly = true)
    public PageResponse<View> page(Long companyId, Long customerId, int page, int size) {
        return PageResponse.of(activities.findByCompanyIdAndCustomerId(companyId, customerId,
                        PageRequest.of(Math.max(page, 0), PageResponse.clampSize(size),
                                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))),
                a -> new View(a.getId(), a.getType().name(), a.getDescription(), a.getReferenceId(), a.getAmount(),
                        a.getActor(), BusinessClock.withOffset(a.getCreatedAt())));
    }
}
