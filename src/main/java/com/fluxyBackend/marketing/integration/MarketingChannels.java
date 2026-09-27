package com.fluxyBackend.marketing.integration;

import com.fluxyBackend.marketing.enums.CampaignChannel;
import org.springframework.stereotype.Component;

import java.util.List;

/** Elige el adaptador de cada canal: el primero que lo soporte, con el manual como último recurso. */
@Component
public class MarketingChannels {

    private final List<ChannelPublisher> publishers;

    public MarketingChannels(List<ChannelPublisher> publishers) {
        this.publishers = publishers;
    }

    public ChannelPublisher forChannel(CampaignChannel channel) {
        return publishers.stream().filter(p -> p.supports(channel)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Sin adaptador para " + channel));
    }
}
