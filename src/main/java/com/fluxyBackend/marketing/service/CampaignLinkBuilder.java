package com.fluxyBackend.marketing.service;

import com.fluxyBackend.entity.Company;
import com.fluxyBackend.marketing.dto.CampaignLink;
import com.fluxyBackend.marketing.entity.MarketingCampaign;
import com.fluxyBackend.marketing.enums.CampaignChannel;
import com.fluxyBackend.marketing.enums.CampaignType;
import com.fluxyBackend.marketing.integration.ChannelPublisher;
import com.fluxyBackend.marketing.integration.MarketingChannels;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Arma el enlace rastreable. Solo lleva el código aleatorio de la campaña, el canal y lo que la
 * tienda ya muestra en su URL (producto o categoría): nunca datos de clientes. Los utm_* dejan
 * que Google Analytics, si el comercio lo conectó, atribuya la visita a la misma campaña.
 */
@Component
public class CampaignLinkBuilder {

    public static final String PARAM = "cmp";

    private final MarketingChannels channels;
    private final String storeBaseUrl;

    public CampaignLinkBuilder(MarketingChannels channels, @Value("${app.frontend_url}") String frontendUrl) {
        this.channels = channels;
        this.storeBaseUrl = frontendUrl.replaceAll("/+$", "");
    }

    public CampaignLink link(MarketingCampaign campaign, Company company, CampaignChannel channel, String targetName) {
        Map<String, String> params = new LinkedHashMap<>();
        if (campaign.getType() == CampaignType.PRODUCT && campaign.getTargetId() != null) {
            params.put("producto", campaign.getTargetId().toString());
        } else if (campaign.getType() == CampaignType.CATEGORY && campaign.getTargetId() != null) {
            params.put("vista", "productos");
            params.put("categoria", campaign.getTargetId().toString());
        }
        params.put(PARAM, campaign.getTrackingCode());
        params.put("utm_source", channel.utmSource());
        params.put("utm_medium", channel.utmMedium());
        params.put("utm_campaign", campaign.getTrackingCode());

        String path = "/store/" + encode(company.getSlug());
        String query = "?" + params.entrySet().stream()
                .map(e -> e.getKey() + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
        String url = storeBaseUrl + path + query;
        String text = text(campaign, company, targetName, url);
        ChannelPublisher publisher = channels.forChannel(channel);
        return new CampaignLink(channel.name(), url, path, query, text,
                publisher.shareUrl(campaign, channel, text, url), publisher.supportsAutoPublish());
    }

    /** El contenido que escribió el comercio, o uno simple armado con lo que se promociona. */
    static String text(MarketingCampaign campaign, Company company, String targetName, String url) {
        StringBuilder text = new StringBuilder();
        if (notBlank(campaign.getTitle())) text.append(campaign.getTitle().strip()).append("\n");
        if (notBlank(campaign.getMessage())) {
            text.append(campaign.getMessage().strip()).append("\n");
        } else if (!notBlank(campaign.getTitle())) {
            text.append(switch (campaign.getType()) {
                case PRODUCT -> "Mirá " + (targetName == null ? "este producto" : targetName) + " en " + company.getName() + ".";
                case CATEGORY -> "Conocé " + (targetName == null ? "nuestra colección" : targetName) + " en " + company.getName() + ".";
                case COUPON -> "Tenemos una promoción para vos en " + company.getName() + ".";
                case STORE -> "Conocé la tienda online de " + company.getName() + ".";
            }).append("\n");
        }
        String cta = notBlank(campaign.getCallToAction()) ? campaign.getCallToAction().strip() : "Pedí acá";
        text.append(cta).append(": ").append(url);
        return text.toString();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
