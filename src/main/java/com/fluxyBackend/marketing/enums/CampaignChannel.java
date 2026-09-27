package com.fluxyBackend.marketing.enums;

import java.util.Locale;

/**
 * Por dónde se comparte la campaña. En V1 todos son distribución manual: Fluxy arma el enlace
 * y el texto, y el comercio lo publica.
 */
public enum CampaignChannel {
    WHATSAPP("whatsapp", "social"),
    INSTAGRAM("instagram", "social"),
    FACEBOOK("facebook", "social"),
    TIKTOK("tiktok", "social"),
    DIRECT("fluxy", "link"),
    QR("qr", "offline");

    private final String utmSource;
    private final String utmMedium;

    CampaignChannel(String utmSource, String utmMedium) {
        this.utmSource = utmSource;
        this.utmMedium = utmMedium;
    }

    public String utmSource() {
        return utmSource;
    }

    public String utmMedium() {
        return utmMedium;
    }

    /** Canal a partir del utm_source del enlace; lo desconocido cuenta como enlace directo. */
    public static CampaignChannel fromSource(String source) {
        if (source == null || source.isBlank()) return null;
        String value = source.trim().toLowerCase(Locale.ROOT);
        for (CampaignChannel channel : values()) {
            if (channel.utmSource.equals(value) || channel.name().toLowerCase(Locale.ROOT).equals(value)) return channel;
        }
        return DIRECT;
    }
}
