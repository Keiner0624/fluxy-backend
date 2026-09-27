package com.fluxyBackend.marketing.service;

import com.fluxyBackend.billing.EntitlementService;
import com.fluxyBackend.billing.Feature;
import com.fluxyBackend.billing.PlanCatalog;
import com.fluxyBackend.entity.Category;
import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Coupon;
import com.fluxyBackend.entity.Prodcut;
import com.fluxyBackend.exception.BusinessException;
import com.fluxyBackend.exception.NotFoundException;
import com.fluxyBackend.marketing.dto.CampaignAnalytics;
import com.fluxyBackend.marketing.dto.CampaignLink;
import com.fluxyBackend.marketing.dto.CampaignMetrics;
import com.fluxyBackend.marketing.dto.CampaignRequest;
import com.fluxyBackend.marketing.dto.CampaignView;
import com.fluxyBackend.marketing.dto.MarketingOverview;
import com.fluxyBackend.marketing.entity.MarketingCampaign;
import com.fluxyBackend.marketing.enums.CampaignChannel;
import com.fluxyBackend.marketing.enums.CampaignObjective;
import com.fluxyBackend.marketing.enums.CampaignStatus;
import com.fluxyBackend.marketing.enums.CampaignType;
import com.fluxyBackend.marketing.enums.SegmentKey;
import com.fluxyBackend.marketing.repository.MarketingCampaignRepository;
import com.fluxyBackend.repository.CategoryRepository;
import com.fluxyBackend.repository.CompanyRepository;
import com.fluxyBackend.repository.CouponRepository;
import com.fluxyBackend.repository.ProductRepository;
import com.fluxyBackend.security.access.Member;
import com.fluxyBackend.security.access.Permission;
import com.fluxyBackend.service.AuditAction;
import com.fluxyBackend.service.AuditService;
import com.fluxyBackend.service.BusinessClock;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Campañas: alta, edición y ciclo de vida. La empresa siempre es la de la sesión; una campaña
 * de otra empresa responde 404, igual que una inexistente.
 */
@Service
@RequiredArgsConstructor
public class CampaignService {

    public static final String CAMPAIGN_LIMIT = "CAMPAIGN_LIMIT";
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final int CODE_LENGTH = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MarketingCampaignRepository campaigns;
    private final ProductRepository products;
    private final CategoryRepository categories;
    private final CouponRepository coupons;
    private final CompanyRepository companies;
    private final EntitlementService entitlements;
    private final AuditService audit;
    private final CampaignAnalyticsService analytics;
    private final CampaignLinkBuilder links;
    private final BusinessClock clock;

    // ─── Lectura ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<CampaignView> list(Member member) {
        List<MarketingCampaign> all = campaigns.findByCompanyIdOrderByCreatedAtDescIdDesc(member.companyId());
        Map<Long, CampaignMetrics> metrics = member.can(Permission.MARKETING_ANALYTICS)
                ? analytics.metricsByCampaign(member.companyId(), CampaignAnalyticsService.BEGINNING, LocalDateTime.now().plusMinutes(1))
                : null;
        Lookups lookups = lookups(member.company(), all);
        return all.stream().map(c -> view(c, lookups,
                metrics == null ? null : metrics.getOrDefault(c.getId(), CampaignMetrics.EMPTY))).toList();
    }

    @Transactional(readOnly = true)
    public CampaignView get(Member member, Long id) {
        return view(member, find(member, id));
    }

    @Transactional(readOnly = true)
    public MarketingOverview overview(Member member, int days) {
        int period = Math.max(1, Math.min(days, 365));
        LocalDateTime to = LocalDateTime.now();
        LocalDateTime from = to.minusDays(period);
        Company company = member.company();
        CampaignMetrics totals = analytics.sum(analytics.metricsByCampaign(company.getId(), from, to.plusMinutes(1)).values());
        return new MarketingOverview(BusinessClock.withOffset(from), BusinessClock.withOffset(to), totals,
                campaigns.countByCompanyIdAndStatusIn(company.getId(), CampaignStatus.LIVE),
                entitlements.activeCampaignLimit(company), entitlements.effectivePlan(company).name(),
                capabilities(company));
    }

    public MarketingOverview.Capabilities capabilities(Company company) {
        return new MarketingOverview.Capabilities(
                entitlements.has(company, Feature.MARKETING_FULL_ANALYTICS),
                entitlements.has(company, Feature.MARKETING_ADVANCED_SEGMENTS),
                entitlements.has(company, Feature.MARKETING_CUSTOM_QR),
                entitlements.has(company, Feature.MARKETING_EXPORT),
                entitlements.has(company, Feature.COUPONS));
    }

    @Transactional(readOnly = true)
    public CampaignAnalytics analytics(Member member, Long id, LocalDate fromDay, LocalDate toDay) {
        MarketingCampaign campaign = find(member, id);
        ZoneId zone = clock.zone(member.companyId());
        LocalDateTime from = fromDay == null ? campaign.getCreatedAt() : clock.startOf(fromDay, zone);
        LocalDateTime to = toDay == null ? LocalDateTime.now() : clock.startOf(toDay.plusDays(1), zone);
        if (!from.isBefore(to)) throw new BusinessException("El rango de fechas es inválido.");
        Company company = member.company();
        return analytics.analytics(campaign, from, to,
                entitlements.has(company, Feature.MARKETING_FULL_ANALYTICS),
                entitlements.has(company, Feature.MARKETING_EXPORT));
    }

    @Transactional(readOnly = true)
    public CampaignLink link(Member member, Long id, String channelName) {
        MarketingCampaign campaign = find(member, id);
        CampaignChannel channel = channelName == null || channelName.isBlank()
                ? campaign.getChannel() : parse(CampaignChannel.class, channelName, "Canal inválido.");
        Lookups lookups = lookups(member.company(), List.of(campaign));
        return links.link(campaign, member.company(), channel, lookups.targetName(campaign));
    }

    // ─── Escritura ───────────────────────────────────────────────────────────

    @Transactional
    public CampaignView create(Member member, CampaignRequest request) {
        MarketingCampaign campaign = new MarketingCampaign();
        campaign.setCompanyId(member.companyId());
        campaign.setCreatedBy(member.user().getId());
        campaign.setTrackingCode(newTrackingCode());
        CampaignType type = parse(CampaignType.class, required(request.type(), "Elegí qué vas a promocionar."), "Tipo de campaña inválido.");
        campaign.setType(type);
        campaign.setObjective(request.objective() == null || request.objective().isBlank()
                ? defaultObjective(type) : parse(CampaignObjective.class, request.objective(), "Objetivo inválido."));
        campaign.setChannel(request.channel() == null || request.channel().isBlank()
                ? CampaignChannel.DIRECT : parse(CampaignChannel.class, request.channel(), "Canal inválido."));
        applyTarget(member.company(), campaign, type, request.targetId(), request.couponId());
        applyContent(member.company(), campaign, request);
        campaign.setStatus(CampaignStatus.DRAFT);
        MarketingCampaign saved = campaigns.save(campaign);
        audit.record(member, AuditAction.CAMPAIGN_CREATED, "CAMPAIGN", saved.getId(),
                Map.of("name", saved.getName(), "type", type.name(), "channel", saved.getChannel().name()));
        return view(member, saved);
    }

    @Transactional
    public CampaignView update(Member member, Long id, CampaignRequest request) {
        MarketingCampaign campaign = find(member, id);
        if (!campaign.getStatus().editable()) {
            throw new BusinessException(HttpStatus.CONFLICT, "CAMPAIGN_CLOSED", "Una campaña finalizada o archivada ya no se edita.");
        }
        if (campaign.getStatus() == CampaignStatus.DRAFT) {
            if (request.type() != null && !request.type().isBlank()) {
                campaign.setType(parse(CampaignType.class, request.type(), "Tipo de campaña inválido."));
            }
            if (request.channel() != null && !request.channel().isBlank()) {
                campaign.setChannel(parse(CampaignChannel.class, request.channel(), "Canal inválido."));
            }
            applyTarget(member.company(), campaign, campaign.getType(), request.targetId(), request.couponId());
        } else {
            // Publicada: lo que se promociona queda fijo para no mezclar resultados; el cupón sí puede cambiar.
            if (campaign.getType() != CampaignType.COUPON) {
                applyCoupon(member.company(), campaign, request.couponId());
            }
        }
        if (request.objective() != null && !request.objective().isBlank()) {
            campaign.setObjective(parse(CampaignObjective.class, request.objective(), "Objetivo inválido."));
        }
        applyContent(member.company(), campaign, request);
        if (CampaignStatus.LIVE.contains(campaign.getStatus())) {
            LocalDateTime now = LocalDateTime.now();
            if (campaign.getEndsAt() != null && !campaign.getEndsAt().isAfter(now)) {
                throw new BusinessException("La fecha de fin ya pasó. Finalizá la campaña o elegí otra fecha.");
            }
            schedule(campaign, now);
        }
        MarketingCampaign saved = campaigns.save(campaign);
        audit.record(member, AuditAction.CAMPAIGN_UPDATED, "CAMPAIGN", saved.getId(), Map.of("name", saved.getName()));
        return view(member, saved);
    }

    @Transactional
    public CampaignView activate(Member member, Long id) {
        // Serializa las activaciones de la empresa: dos a la vez podían pasarse del límite.
        Company company = companies.findByIdForUpdate(member.companyId())
                .orElseThrow(() -> new NotFoundException("Empresa no encontrada"));
        MarketingCampaign campaign = find(member, id);
        if (!campaign.getStatus().canActivate()) throw transition("activar", campaign);
        LocalDateTime now = LocalDateTime.now();
        if (campaign.getEndsAt() != null && !campaign.getEndsAt().isAfter(now)) {
            throw new BusinessException("La fecha de fin ya pasó. Cambiala antes de activar la campaña.");
        }
        ensurePublishable(company, campaign);
        if (!CampaignStatus.LIVE.contains(campaign.getStatus())) {
            int limit = entitlements.activeCampaignLimit(company);
            long live = campaigns.countByCompanyIdAndStatusIn(company.getId(), CampaignStatus.LIVE);
            if (limit != PlanCatalog.UNLIMITED && live >= limit) {
                throw new BusinessException(HttpStatus.FORBIDDEN, CAMPAIGN_LIMIT,
                        "Tu plan permite " + limit + (limit == 1 ? " campaña activa" : " campañas activas")
                                + " a la vez. Pausá o finalizá otra, o mejorá tu plan.",
                        Map.of("limit", limit, "live", live, "currentPlan", entitlements.effectivePlan(company).name()));
            }
        }
        schedule(campaign, now);
        MarketingCampaign saved = campaigns.save(campaign);
        audit.record(member, AuditAction.CAMPAIGN_ACTIVATED, "CAMPAIGN", saved.getId(),
                Map.of("name", saved.getName(), "status", saved.getStatus().name()));
        return view(member, saved);
    }

    @Transactional
    public CampaignView pause(Member member, Long id) {
        MarketingCampaign campaign = find(member, id);
        if (!campaign.getStatus().canPause()) throw transition("pausar", campaign);
        campaign.setStatus(CampaignStatus.PAUSED);
        MarketingCampaign saved = campaigns.save(campaign);
        audit.record(member, AuditAction.CAMPAIGN_PAUSED, "CAMPAIGN", saved.getId(), Map.of("name", saved.getName()));
        return view(member, saved);
    }

    @Transactional
    public CampaignView finish(Member member, Long id) {
        MarketingCampaign campaign = find(member, id);
        if (!campaign.getStatus().canFinish()) throw transition("finalizar", campaign);
        campaign.setStatus(CampaignStatus.FINISHED);
        campaign.setFinishedAt(LocalDateTime.now());
        MarketingCampaign saved = campaigns.save(campaign);
        audit.record(member, AuditAction.CAMPAIGN_FINISHED, "CAMPAIGN", saved.getId(), Map.of("name", saved.getName()));
        return view(member, saved);
    }

    @Transactional
    public CampaignView archive(Member member, Long id) {
        MarketingCampaign campaign = find(member, id);
        if (!campaign.getStatus().canArchive()) throw transition("archivar", campaign);
        campaign.setStatus(CampaignStatus.ARCHIVED);
        MarketingCampaign saved = campaigns.save(campaign);
        audit.record(member, AuditAction.CAMPAIGN_ARCHIVED, "CAMPAIGN", saved.getId(), Map.of("name", saved.getName()));
        return view(member, saved);
    }

    /** Solo borradores: nunca aceptaron visitas, así que no hay resultados que perder. */
    @Transactional
    public void delete(Member member, Long id) {
        MarketingCampaign campaign = find(member, id);
        if (campaign.getStatus() != CampaignStatus.DRAFT) {
            throw new BusinessException(HttpStatus.CONFLICT, "CAMPAIGN_NOT_DRAFT",
                    "Solo se eliminan borradores. Una campaña publicada se finaliza y se archiva para conservar sus resultados.");
        }
        campaigns.delete(campaign);
        audit.record(member, AuditAction.CAMPAIGN_DELETED, "CAMPAIGN", id, Map.of("name", campaign.getName()));
    }

    /** Programadas que ya empiezan y vigentes cuya fecha de fin pasó. */
    @Transactional
    public int applyScheduledTransitions() {
        LocalDateTime now = LocalDateTime.now();
        int changed = 0;
        for (MarketingCampaign c : campaigns.findByStatusInAndEndsAtLessThanEqual(
                EnumSet.of(CampaignStatus.SCHEDULED, CampaignStatus.ACTIVE, CampaignStatus.PAUSED), now)) {
            c.setStatus(CampaignStatus.FINISHED);
            c.setFinishedAt(c.getEndsAt());
            changed++;
        }
        for (MarketingCampaign c : campaigns.findByStatusAndStartsAtLessThanEqual(CampaignStatus.SCHEDULED, now)) {
            c.setStatus(CampaignStatus.ACTIVE);
            if (c.getActivatedAt() == null) c.setActivatedAt(c.getStartsAt());
            changed++;
        }
        return changed;
    }

    // ─── Reglas ──────────────────────────────────────────────────────────────

    private void schedule(MarketingCampaign campaign, LocalDateTime now) {
        if (campaign.getStartsAt() != null && campaign.getStartsAt().isAfter(now)) {
            campaign.setStatus(CampaignStatus.SCHEDULED);
        } else {
            campaign.setStatus(CampaignStatus.ACTIVE);
            if (campaign.getActivatedAt() == null) campaign.setActivatedAt(now);
        }
    }

    /** Lo que promociona tiene que seguir existiendo y poder usarse. */
    private void ensurePublishable(Company company, MarketingCampaign campaign) {
        switch (campaign.getType()) {
            case PRODUCT -> {
                Prodcut product = products.findByIdAndCompany(campaign.getTargetId(), company)
                        .orElseThrow(() -> new BusinessException("El producto de la campaña ya no existe."));
                if (product.getStatus() == Prodcut.Status.HIDDEN) {
                    throw new BusinessException("El producto está oculto en la tienda. Mostralo antes de activar la campaña.");
                }
            }
            case CATEGORY -> categories.findByIdAndCompany(campaign.getTargetId(), company)
                    .orElseThrow(() -> new BusinessException("La categoría de la campaña ya no existe."));
            default -> {
            }
        }
        if (campaign.getCouponId() != null) {
            entitlements.require(company, Feature.COUPONS);
            Coupon coupon = ownCoupon(company, campaign.getCouponId());
            if (!coupon.isActive()) throw new BusinessException("El cupón " + coupon.getCode() + " está desactivado.");
            if (coupon.getExpiresAt() != null && !coupon.getExpiresAt().isAfter(LocalDateTime.now())) {
                throw new BusinessException("El cupón " + coupon.getCode() + " ya venció.");
            }
        }
    }

    private void applyTarget(Company company, MarketingCampaign campaign, CampaignType type, Long targetId, Long couponId) {
        switch (type) {
            case STORE -> campaign.setTargetId(null);
            case PRODUCT -> {
                if (targetId == null) throw new BusinessException("Elegí el producto que vas a promocionar.");
                products.findByIdAndCompany(targetId, company)
                        .orElseThrow(() -> new BusinessException("El producto no existe en tu tienda."));
                campaign.setTargetId(targetId);
            }
            case CATEGORY -> {
                if (targetId == null) throw new BusinessException("Elegí la categoría que vas a promocionar.");
                categories.findByIdAndCompany(targetId, company)
                        .orElseThrow(() -> new BusinessException("La categoría no existe en tu tienda."));
                campaign.setTargetId(targetId);
            }
            case COUPON -> {
                Long id = couponId != null ? couponId : targetId;
                if (id == null) throw new BusinessException("Elegí el cupón de la promoción.");
                campaign.setTargetId(id);
                applyCoupon(company, campaign, id);
                return;
            }
        }
        applyCoupon(company, campaign, couponId);
    }

    private void applyCoupon(Company company, MarketingCampaign campaign, Long couponId) {
        if (couponId == null) {
            campaign.setCouponId(null);
            return;
        }
        // Los cupones son del plan Pro: sin él el checkout no los aceptaría.
        entitlements.require(company, Feature.COUPONS);
        ownCoupon(company, couponId);
        campaign.setCouponId(couponId);
    }

    private void applyContent(Company company, MarketingCampaign campaign, CampaignRequest request) {
        String name = clean(request.name());
        if (name != null) campaign.setName(name);
        if (campaign.getName() == null) throw new BusinessException("Poné un nombre a la campaña.");
        campaign.setTitle(clean(request.title()));
        campaign.setMessage(clean(request.message()));
        campaign.setCallToAction(clean(request.callToAction()));
        campaign.setImageUrl(clean(request.imageUrl()));

        SegmentKey segment = request.segment() == null || request.segment().isBlank()
                ? null : parse(SegmentKey.class, request.segment(), "Segmento inválido.");
        if (segment != null && segment.advanced()) entitlements.require(company, Feature.MARKETING_ADVANCED_SEGMENTS);
        campaign.setSegment(segment);
        if (segment == SegmentKey.CATEGORY_BUYERS) {
            if (request.segmentCategoryId() == null) throw new BusinessException("Elegí la categoría del segmento.");
            categories.findByIdAndCompany(request.segmentCategoryId(), company)
                    .orElseThrow(() -> new BusinessException("La categoría del segmento no existe en tu tienda."));
            campaign.setSegmentCategoryId(request.segmentCategoryId());
        } else {
            campaign.setSegmentCategoryId(null);
        }

        LocalDateTime startsAt = serverTime(request.startsAt());
        LocalDateTime endsAt = serverTime(request.endsAt());
        if (startsAt != null && endsAt != null && !endsAt.isAfter(startsAt)) {
            throw new BusinessException("La fecha de fin tiene que ser posterior al inicio.");
        }
        campaign.setStartsAt(startsAt);
        campaign.setEndsAt(endsAt);
    }

    private Coupon ownCoupon(Company company, Long couponId) {
        return coupons.findById(couponId)
                .filter(c -> c.getCompany() != null && c.getCompany().getId().equals(company.getId()))
                .orElseThrow(() -> new BusinessException("El cupón no existe en tu tienda."));
    }

    private MarketingCampaign find(Member member, Long id) {
        return campaigns.findByIdAndCompanyId(id, member.companyId())
                .orElseThrow(() -> new NotFoundException("Campaña no encontrada"));
    }

    private String newTrackingCode() {
        for (int attempt = 0; attempt < 5; attempt++) {
            StringBuilder code = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            if (!campaigns.existsByTrackingCode(code.toString())) return code.toString();
        }
        throw new IllegalStateException("No se pudo generar un código de campaña único");
    }

    private static CampaignObjective defaultObjective(CampaignType type) {
        return switch (type) {
            case PRODUCT -> CampaignObjective.SELL_PRODUCT;
            case COUPON -> CampaignObjective.PROMOTION;
            default -> CampaignObjective.VISITS;
        };
    }

    private static BusinessException transition(String verb, MarketingCampaign campaign) {
        return new BusinessException(HttpStatus.CONFLICT, "CAMPAIGN_INVALID_TRANSITION",
                "No se puede " + verb + " una campaña en estado " + campaign.getStatus().name() + ".",
                Map.of("status", campaign.getStatus().name()));
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String message) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(message);
        }
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) throw new BusinessException(message);
        return value;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static LocalDateTime serverTime(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
    }

    // ─── Vistas ──────────────────────────────────────────────────────────────

    private CampaignView view(Member member, MarketingCampaign campaign) {
        CampaignMetrics metrics = null;
        if (member.can(Permission.MARKETING_ANALYTICS)) {
            metrics = analytics.metricsByCampaign(member.companyId(), CampaignAnalyticsService.BEGINNING,
                    LocalDateTime.now().plusMinutes(1)).getOrDefault(campaign.getId(), CampaignMetrics.EMPTY);
        }
        return view(campaign, lookups(member.company(), List.of(campaign)), metrics);
    }

    private CampaignView view(MarketingCampaign c, Lookups lookups, CampaignMetrics metrics) {
        List<String> actions = new ArrayList<>();
        if (c.getStatus().editable()) actions.add("EDIT");
        if (c.getStatus().canActivate()) actions.add("ACTIVATE");
        if (c.getStatus().canPause()) actions.add("PAUSE");
        if (c.getStatus().canFinish()) actions.add("FINISH");
        if (c.getStatus().canArchive()) actions.add("ARCHIVE");
        if (c.getStatus() == CampaignStatus.DRAFT) actions.add("DELETE");
        Coupon coupon = c.getCouponId() == null ? null : lookups.coupons().get(c.getCouponId());
        return new CampaignView(c.getId(), c.getName(), c.getType().name(), c.getObjective().name(), c.getTargetId(),
                lookups.targetName(c), lookups.targetImage(c), c.getCouponId(), coupon == null ? null : coupon.getCode(),
                c.getChannel().name(), c.getStatus().name(), c.getTrackingCode(), c.getTitle(), c.getMessage(),
                c.getCallToAction(), c.getImageUrl(), c.getSegment() == null ? null : c.getSegment().name(),
                c.getSegmentCategoryId(), BusinessClock.withOffset(c.getStartsAt()), BusinessClock.withOffset(c.getEndsAt()),
                BusinessClock.withOffset(c.getActivatedAt()), BusinessClock.withOffset(c.getFinishedAt()),
                BusinessClock.withOffset(c.getCreatedAt()), BusinessClock.withOffset(c.getUpdatedAt()),
                c.acceptsAttribution(LocalDateTime.now()), actions, metrics);
    }

    private Lookups lookups(Company company, Collection<MarketingCampaign> list) {
        Set<Long> productIds = list.stream().filter(c -> c.getType() == CampaignType.PRODUCT && c.getTargetId() != null)
                .map(MarketingCampaign::getTargetId).collect(Collectors.toSet());
        Map<Long, Prodcut> productMap = productIds.isEmpty() ? Map.of()
                : products.findByCompanyIdAndIdIn(company.getId(), productIds).stream()
                .collect(Collectors.toMap(Prodcut::getId, Function.identity()));
        boolean needsCategories = list.stream().anyMatch(c -> c.getType() == CampaignType.CATEGORY);
        Map<Long, Category> categoryMap = needsCategories
                ? categories.findByCompanyOrderByNameAsc(company).stream().collect(Collectors.toMap(Category::getId, Function.identity()))
                : Map.of();
        boolean needsCoupons = list.stream().anyMatch(c -> c.getCouponId() != null);
        Map<Long, Coupon> couponMap = needsCoupons
                ? coupons.findByCompany(company).stream().collect(Collectors.toMap(Coupon::getId, Function.identity()))
                : Map.of();
        return new Lookups(productMap, categoryMap, couponMap);
    }

    private record Lookups(Map<Long, Prodcut> products, Map<Long, Category> categories, Map<Long, Coupon> coupons) {

        String targetName(MarketingCampaign c) {
            if (c.getTargetId() == null) return null;
            return switch (c.getType()) {
                case PRODUCT -> Optional.ofNullable(products.get(c.getTargetId())).map(Prodcut::getName).orElse(null);
                case CATEGORY -> Optional.ofNullable(categories.get(c.getTargetId())).map(Category::getName).orElse(null);
                case COUPON -> Optional.ofNullable(coupons.get(c.getTargetId())).map(Coupon::getCode).orElse(null);
                case STORE -> null;
            };
        }

        String targetImage(MarketingCampaign c) {
            if (c.getType() != CampaignType.PRODUCT || c.getTargetId() == null) return null;
            return Optional.ofNullable(products.get(c.getTargetId())).map(Prodcut::getImageUrl).orElse(null);
        }
    }
}
