package com.fluxyBackend.marketing.integration;

import com.fluxyBackend.marketing.entity.MarketingCampaign;
import com.fluxyBackend.marketing.enums.CampaignChannel;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Distribución manual: arma los enlaces de "compartir" que abren la app de cada red con el
 * mensaje listo. Instagram y TikTok no tienen uno web: ahí se copia el texto y se descarga la pieza.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class ManualSharePublisher implements ChannelPublisher {

    @Override
    public boolean supports(CampaignChannel channel) {
        return true;
    }

    @Override
    public boolean supportsAutoPublish() {
        return false;
    }

    @Override
    public String shareUrl(MarketingCampaign campaign, CampaignChannel channel, String text, String url) {
        return switch (channel) {
            case WHATSAPP -> "https://wa.me/?text=" + encode(text);
            case FACEBOOK -> "https://www.facebook.com/sharer/sharer.php?u=" + encode(url);
            default -> null;
        };
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
