package com.fluxyBackend.marketing.enums;

import java.util.EnumSet;
import java.util.Set;

/**
 * Ciclo de vida: DRAFT → (SCHEDULED →) ACTIVE ⇄ PAUSED → FINISHED → ARCHIVED.
 * Solo ACTIVE acepta atribución, y además dentro de su vigencia.
 */
public enum CampaignStatus {
    DRAFT,
    SCHEDULED,
    ACTIVE,
    PAUSED,
    FINISHED,
    ARCHIVED;

    /** Cuentan para el límite de campañas activas del plan. */
    public static final Set<CampaignStatus> LIVE = EnumSet.of(SCHEDULED, ACTIVE);

    public boolean canActivate() {
        return this == DRAFT || this == PAUSED || this == SCHEDULED;
    }

    public boolean canPause() {
        return this == ACTIVE || this == SCHEDULED;
    }

    public boolean canFinish() {
        return this == ACTIVE || this == PAUSED || this == SCHEDULED;
    }

    public boolean canArchive() {
        return this == DRAFT || this == FINISHED;
    }

    public boolean editable() {
        return this != FINISHED && this != ARCHIVED;
    }
}
