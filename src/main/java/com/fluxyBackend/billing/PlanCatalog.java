package com.fluxyBackend.billing;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Company.Plan;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Catálogo de planes: precio, límites y funciones. Es la única fuente de estos datos;
 * cambiar un precio o un beneficio es cambiar este archivo y subir la versión.
 */
public final class PlanCatalog {

    public static final String CURRENCY = "PEN";
    public static final String INTERVAL = "MONTHLY";
    public static final int VERSION = 1;
    /** Sin límite (productos o campañas). */
    public static final int UNLIMITED = -1;

    /** activeCampaignLimit: campañas de marketing activas o programadas a la vez. */
    public record PlanInfo(Plan code, String name, BigDecimal monthlyPrice, int productLimit,
                           int activeCampaignLimit, Set<Feature> features, int rank) {
        public boolean paid() {
            return monthlyPrice.signum() > 0;
        }
    }

    private static final Set<Feature> PRO_FEATURES = EnumSet.of(
            Feature.METRICS, Feature.REPORTS, Feature.COUPONS, Feature.CUSTOM_STYLE, Feature.WHATSAPP,
            Feature.MARKETING_FULL_ANALYTICS, Feature.MARKETING_ADVANCED_SEGMENTS, Feature.MARKETING_CUSTOM_QR);
    private static final Set<Feature> BUSINESS_FEATURES = union(PRO_FEATURES,
            EnumSet.of(Feature.CUSTOM_DOMAIN, Feature.AI_DESCRIPTIONS, Feature.NO_BRANDING, Feature.MARKETING_EXPORT));

    private static final Map<Plan, PlanInfo> PLANS = Map.of(
            Plan.FREE, new PlanInfo(Plan.FREE, "Free", BigDecimal.ZERO.setScale(2), 10, 2, EnumSet.noneOf(Feature.class), 0),
            Plan.PRO, new PlanInfo(Plan.PRO, "Pro", new BigDecimal("39.00"), 100, 20, PRO_FEATURES, 1),
            Plan.BUSINESS, new PlanInfo(Plan.BUSINESS, "Business", new BigDecimal("59.00"), UNLIMITED, UNLIMITED, BUSINESS_FEATURES, 2));

    private PlanCatalog() {
    }

    public static PlanInfo info(Plan plan) {
        return PLANS.get(plan == null ? Plan.FREE : plan);
    }

    public static List<PlanInfo> all() {
        return List.of(PLANS.get(Plan.FREE), PLANS.get(Plan.PRO), PLANS.get(Plan.BUSINESS));
    }

    /** El plan más barato que incluye la función. */
    public static Plan cheapestWith(Feature feature) {
        return all().stream().filter(p -> p.features().contains(feature)).findFirst()
                .map(PlanInfo::code).orElse(Plan.BUSINESS);
    }

    /**
     * Plan con el que se atiende a la empresa ahora mismo. Un plan pago cuyo último periodo pagado
     * ya terminó vale como FREE aunque la tarea de fin de periodo todavía no haya pasado.
     */
    public static Plan effectivePlan(Company company) {
        if (company == null || company.getPlan() == null || company.getPlan() == Plan.FREE) return Plan.FREE;
        LocalDateTime paidUntil = company.getPlanExpiresAt();
        if (paidUntil != null && !paidUntil.isAfter(LocalDateTime.now())) return Plan.FREE;
        return company.getPlan();
    }

    public static boolean has(Company company, Feature feature) {
        return info(effectivePlan(company)).features().contains(feature);
    }

    private static Set<Feature> union(Set<Feature> a, Set<Feature> b) {
        Set<Feature> result = EnumSet.copyOf(a);
        result.addAll(b);
        return result;
    }
}
