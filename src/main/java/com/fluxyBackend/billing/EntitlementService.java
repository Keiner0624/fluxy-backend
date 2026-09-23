package com.fluxyBackend.billing;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.entity.Company.Plan;
import com.fluxyBackend.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Qué puede usar una empresa según su plan vigente. El panel solo lo muestra;
 * cada endpoint lo vuelve a comprobar acá.
 */
@Service
public class EntitlementService {

    public static final String PLAN_REQUIRED = "PLAN_REQUIRED";

    public Plan effectivePlan(Company company) {
        return PlanCatalog.effectivePlan(company);
    }

    public boolean has(Company company, Feature feature) {
        return PlanCatalog.has(company, feature);
    }

    public void require(Company company, Feature feature) {
        if (has(company, feature)) return;
        Plan required = PlanCatalog.cheapestWith(feature);
        throw new BusinessException(HttpStatus.FORBIDDEN, PLAN_REQUIRED,
                feature.label() + " está disponible desde el plan " + PlanCatalog.info(required).name() + ".",
                Map.of("feature", feature.name(), "requiredPlan", required.name(),
                        "currentPlan", effectivePlan(company).name()));
    }

    /** Máximo de productos; PlanCatalog.UNLIMITED si no hay tope. */
    public int productLimit(Company company) {
        return PlanCatalog.info(effectivePlan(company)).productLimit();
    }
}
