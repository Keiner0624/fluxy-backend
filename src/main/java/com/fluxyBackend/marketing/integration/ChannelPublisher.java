package com.fluxyBackend.marketing.integration;

import com.fluxyBackend.marketing.entity.MarketingCampaign;
import com.fluxyBackend.marketing.enums.CampaignChannel;

/**
 * Adaptador hacia un canal externo. En V1 solo existe la distribución manual
 * (ManualSharePublisher); una integración con Meta, TikTok o la API de WhatsApp implementa
 * esta interfaz, declara supportsAutoPublish y la elige MarketingChannels sin tocar el resto.
 * Los tokens de esos proveedores viven en el backend, nunca en el panel.
 */
public interface ChannelPublisher {

    /** Canales que atiende. */
    boolean supports(CampaignChannel channel);

    /** true si puede publicar por su cuenta; false: el comercio copia y comparte. */
    boolean supportsAutoPublish();

    /** Enlace para compartir en el canal con el texto y la URL rastreable; null si el canal no tiene uno. */
    String shareUrl(MarketingCampaign campaign, CampaignChannel channel, String text, String url);
}
