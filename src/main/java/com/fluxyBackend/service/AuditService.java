package com.fluxyBackend.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import com.fluxyBackend.DTOs.PageResponse;
import com.fluxyBackend.entity.AuditLog;
import com.fluxyBackend.entity.User;
import com.fluxyBackend.repository.AuditLogRepository;
import com.fluxyBackend.security.ClientInfo;
import com.fluxyBackend.security.access.Member;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Registro de acciones relevantes.
 *
 * record() participa de la transacción de la operación: si la operación se
 * revierte, su registro también. recordSecurityEvent() usa una transacción
 * propia, porque un login fallido tiene que quedar aunque la petición falle.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    /** Retención: un año alcanza para investigar; más solo acumula datos. */
    private static final int RETENTION_DAYS = 365;
    private static final Set<String> SECRET_KEYS = Set.of("password", "token", "code", "secret", "otp", "refresh");

    private final AuditLogRepository repository;
    private final JsonMapper objectMapper;
    private final BusinessClock clock;

    public record AuditView(Long id, String action, String entityType, String entityId, Long actorUserId,
                            String actorLabel, Map<String, Object> metadata, OffsetDateTime createdAt) {}

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(Member member, String action, String entityType, Object entityId, Map<String, Object> metadata) {
        save(member.companyId(), member.user().getId(), member.displayName(), action, entityType, entityId, metadata);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(Long companyId, User actor, String action, String entityType, Object entityId,
                       Map<String, Object> metadata) {
        save(companyId, actor == null ? null : actor.getId(), label(actor), action, entityType, entityId, metadata);
    }

    /** En transacción propia: queda registrado aunque la petición termine en error. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSecurityEvent(Long companyId, User actor, String actorLabel, String action,
                                    Map<String, Object> metadata) {
        save(companyId, actor == null ? null : actor.getId(),
                actorLabel != null ? actorLabel : label(actor), action, "USER",
                actor == null ? null : actor.getId(), metadata);
    }

    public PageResponse<AuditView> list(Long companyId, String action, Long actorUserId, LocalDate from, LocalDate to,
                                        int page, int size) {
        var zone = clock.zone(companyId);
        Specification<AuditLog> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("companyId"), companyId));
            if (action != null && !action.isBlank()) predicates.add(cb.equal(root.get("action"), action));
            if (actorUserId != null) predicates.add(cb.equal(root.get("actorUserId"), actorUserId));
            if (from != null) predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), clock.startOf(from, zone)));
            if (to != null) predicates.add(cb.lessThan(root.get("createdAt"), clock.startOf(to.plusDays(1), zone)));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return PageResponse.of(repository.findAll(spec, PageRequest.of(Math.max(page, 0), PageResponse.clampSize(size),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))), this::view);
    }

    public List<String> actions(Long companyId) {
        return repository.findActions(companyId);
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "America/Lima")
    @Transactional
    public void purgeOld() {
        int deleted = repository.deleteOlderThan(LocalDateTime.now().minusDays(RETENTION_DAYS));
        if (deleted > 0) log.info("Auditoría: {} registros de más de {} días eliminados", deleted, RETENTION_DAYS);
    }

    private void save(Long companyId, Long actorUserId, String actorLabel, String action, String entityType,
                      Object entityId, Map<String, Object> metadata) {
        AuditLog entry = new AuditLog();
        entry.setCompanyId(companyId);
        entry.setActorUserId(actorUserId);
        entry.setActorLabel(truncate(actorLabel, 150));
        entry.setAction(action);
        entry.setEntityType(entityType);
        entry.setEntityId(entityId == null ? null : truncate(String.valueOf(entityId), 64));
        entry.setMetadata(json(metadata));
        entry.setIpHash(ClientInfo.ipHash(ClientInfo.currentRequest() == null ? null : ClientInfo.currentIp()));
        entry.setRequestId(ClientInfo.requestId());
        repository.save(entry);
    }

    private AuditView view(AuditLog log) {
        Map<String, Object> metadata = Map.of();
        if (log.getMetadata() != null) {
            try {
                metadata = objectMapper.readValue(log.getMetadata(), LinkedHashMap.class);
            } catch (JacksonException ignored) {
                metadata = Map.of("raw", log.getMetadata());
            }
        }
        return new AuditView(log.getId(), log.getAction(), log.getEntityType(), log.getEntityId(),
                log.getActorUserId(), log.getActorLabel(), metadata, BusinessClock.withOffset(log.getCreatedAt()));
    }

    /** Nunca guarda contraseñas, códigos ni tokens, aunque alguien los pase por error. */
    private String json(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return null;
        Map<String, Object> clean = new LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            String lower = key.toLowerCase(Locale.ROOT);
            if (SECRET_KEYS.stream().noneMatch(lower::contains)) clean.put(key, value);
        });
        try {
            return objectMapper.writeValueAsString(clean);
        } catch (JacksonException e) {
            return null;
        }
    }

    private static String label(User user) {
        if (user == null) return null;
        return user.getFullName() == null || user.getFullName().isBlank() ? user.getEmail() : user.getFullName();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
