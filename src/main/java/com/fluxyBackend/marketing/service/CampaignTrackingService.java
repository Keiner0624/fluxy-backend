package com.fluxyBackend.marketing.service;

import com.fluxyBackend.billing.Feature;
import com.fluxyBackend.billing.PlanCatalog;
import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Coupon;
import com.fluxyBackend.entity.Order;
import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.marketing.dto.TrackEventRequest;
import com.fluxyBackend.marketing.dto.TrackEventResponse;
import com.fluxyBackend.marketing.entity.CampaignEvent;
import com.fluxyBackend.marketing.entity.MarketingCampaign;
import com.fluxyBackend.marketing.enums.CampaignChannel;
import com.fluxyBackend.marketing.enums.CampaignEventType;
import com.fluxyBackend.marketing.enums.CampaignType;
import com.fluxyBackend.marketing.repository.CampaignEventRepository;
import com.fluxyBackend.marketing.repository.MarketingCampaignRepository;
import com.fluxyBackend.repository.CouponRepository;
import com.fluxyBackend.repository.OrderRepository;
import com.fluxyBackend.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Registro del embudo desde la tienda pública y atribución de pedidos.
 *
 * Modelo de atribución (V1): el pedido se atribuye a la última campaña cuyo enlace abrió ese
 * navegador en los últimos ATTRIBUTION_DAYS días, siempre que la campaña siga vigente. Los
 * pasos intermedios solo cuentan si antes hubo una visita desde el enlace: un evento suelto
 * con un código válido no alcanza para ensuciar las métricas.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CampaignTrackingService {

    public static final int ATTRIBUTION_DAYS = 7;

    private final MarketingCampaignRepository campaigns;
    private final CampaignEventRepository events;
    private final ProductRepository products;
    private final CouponRepository coupons;
    private final OrderRepository orders;

    public TrackEventResponse track(Company company, TrackEventRequest request) {
        CampaignEventType type = parseType(request.type());
        LocalDateTime now = LocalDateTime.now();
        Optional<MarketingCampaign> found = campaigns.findByTrackingCode(request.code())
                .filter(c -> c.getCompanyId().equals(company.getId()));
        // Código ajeno, inexistente o fuera de vigencia: misma respuesta, sin decir cuál.
        if (found.isEmpty() || !found.get().acceptsAttribution(now)) return TrackEventResponse.rejected(ATTRIBUTION_DAYS);
        MarketingCampaign campaign = found.get();

        CampaignEvent event = new CampaignEvent();
        event.setCampaignId(campaign.getId());
        event.setCompanyId(company.getId());
        event.setType(type);
        event.setSessionId(request.sessionId());
        event.setOccurredAt(now);

        if (type == CampaignEventType.VIEW) {
            CampaignChannel source = CampaignChannel.fromSource(request.source());
            event.setSource(source == null ? campaign.getChannel() : source);
            event.setDedupeKey("V|" + request.sessionId() + "|" + now.toLocalDate());
            boolean recorded = save(event);
            return new TrackEventResponse(true, recorded, target(campaign), suggestedCoupon(campaign, company),
                    ATTRIBUTION_DAYS);
        }

        Optional<CampaignEvent> view = events.findFirstByCampaignIdAndSessionIdAndTypeAndOccurredAtAfterOrderByOccurredAtDescIdDesc(
                campaign.getId(), request.sessionId(), CampaignEventType.VIEW, now.minusDays(ATTRIBUTION_DAYS));
        if (view.isEmpty()) return TrackEventResponse.rejected(ATTRIBUTION_DAYS);
        event.setSource(view.get().getSource());

        String dedupe;
        if (type == CampaignEventType.PRODUCT_VIEW || type == CampaignEventType.ADD_TO_CART) {
            Long productId = request.productId();
            if (productId == null || products.findByCompanyIdAndIdIn(company.getId(), List.of(productId)).isEmpty()) {
                return TrackEventResponse.rejected(ATTRIBUTION_DAYS);
            }
            event.setProductId(productId);
            dedupe = (type == CampaignEventType.PRODUCT_VIEW ? "P|" : "A|") + request.sessionId() + "|" + productId + "|" + now.toLocalDate();
        } else {
            dedupe = "C|" + request.sessionId() + "|" + now.toLocalDate();
        }
        event.setDedupeKey(dedupe);
        return new TrackEventResponse(true, save(event), null, null, ATTRIBUTION_DAYS);
    }

    /** En su propia transacción: corre después de confirmado el pedido. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean attributeOrder(Long orderId, Long companyId, String sessionId) {
        return orders.findByIdAndCompanyId(orderId, companyId)
                .map(order -> attributeOrder(order, sessionId))
                .orElse(false);
    }

    /**
     * Atribuye un pedido de la tienda a la última campaña vigente que vio esa sesión.
     * Se llama después de confirmar el pedido; nunca lo frena ni lo hace fallar.
     */
    public boolean attributeOrder(Order order, String sessionId) {
        if (order == null || order.getCompany() == null || sessionId == null || sessionId.isBlank()) return false;
        if (events.existsByOrderIdAndType(order.getId(), CampaignEventType.ORDER_COMPLETED)) return false;
        LocalDateTime now = LocalDateTime.now();
        Long companyId = order.getCompany().getId();
        List<CampaignEvent> views = events.findTop10ByCompanyIdAndSessionIdAndTypeAndOccurredAtAfterOrderByOccurredAtDescIdDesc(
                companyId, sessionId, CampaignEventType.VIEW, now.minusDays(ATTRIBUTION_DAYS));
        Set<Long> tried = new HashSet<>();
        for (CampaignEvent view : views) {
            if (!tried.add(view.getCampaignId())) continue;
            MarketingCampaign campaign = campaigns.findById(view.getCampaignId()).orElse(null);
            if (campaign == null || !campaign.getCompanyId().equals(companyId) || !campaign.acceptsAttribution(now)) continue;

            CampaignEvent event = new CampaignEvent();
            event.setCampaignId(campaign.getId());
            event.setCompanyId(companyId);
            event.setType(CampaignEventType.ORDER_COMPLETED);
            event.setSessionId(sessionId);
            event.setSource(view.getSource());
            event.setOrderId(order.getId());
            event.setCustomerId(order.getCustomerId());
            event.setAmount(order.getTotal());
            event.setDedupeKey("O|" + order.getId());
            event.setOccurredAt(now);
            return save(event);
        }
        return false;
    }

    private boolean save(CampaignEvent event) {
        if (events.existsByCampaignIdAndDedupeKey(event.getCampaignId(), event.getDedupeKey())) return false;
        try {
            events.saveAndFlush(event);
            return true;
        } catch (DataIntegrityViolationException e) {
            // Dos pestañas a la vez: la otra ya lo contó.
            return false;
        }
    }

    private static TrackEventResponse.Target target(MarketingCampaign campaign) {
        if (campaign.getType() == CampaignType.PRODUCT || campaign.getType() == CampaignType.CATEGORY) {
            return new TrackEventResponse.Target(campaign.getType().name(), campaign.getTargetId());
        }
        return null;
    }

    /** Cupón para precargar en el checkout: solo si hoy se puede usar. */
    private String suggestedCoupon(MarketingCampaign campaign, Company company) {
        if (campaign.getCouponId() == null || !PlanCatalog.has(company, Feature.COUPONS)) return null;
        Coupon coupon = coupons.findById(campaign.getCouponId()).orElse(null);
        if (coupon == null || coupon.getCompany() == null || !company.getId().equals(coupon.getCompany().getId())) return null;
        if (!coupon.isActive()) return null;
        if (coupon.getExpiresAt() != null && !coupon.getExpiresAt().isAfter(LocalDateTime.now())) return null;
        if (coupon.getUsageLimit() != null && coupon.getUsageCount() != null && coupon.getUsageCount() >= coupon.getUsageLimit()) return null;
        return coupon.getCode();
    }

    private static CampaignEventType parseType(String value) {
        try {
            CampaignEventType type = CampaignEventType.valueOf(value.trim().toUpperCase(Locale.ROOT));
            if (CampaignEventType.FROM_STORE.contains(type)) return type;
        } catch (IllegalArgumentException | NullPointerException ignored) {
            // Cae al error de abajo.
        }
        throw new BusinessException("Tipo de evento inválido.");
    }
}
