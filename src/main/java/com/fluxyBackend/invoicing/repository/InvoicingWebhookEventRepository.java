package com.fluxyBackend.invoicing.repository;

import com.fluxyBackend.invoicing.entity.InvoicingWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoicingWebhookEventRepository extends JpaRepository<InvoicingWebhookEvent, Long> {

    boolean existsByProviderAndEventId(String provider, String eventId);
}
