package com.fluxyBackend.service;

public record OrderCreatedEvent(Long orderId, Long companyId) {
}
