package com.sayohno.marketing.campaign.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sayohno.marketing.campaign.service.CampaignFlowService;
import com.sayohno.marketing.common.outbox.InboxGuard;
import com.sayohno.marketing.common.outbox.OutboxEvent;
import com.sayohno.marketing.common.outbox.OutboxHandler;
import org.springframework.stereotype.Component;

/** Conversion projections are committed by the producer; receipt is an idempotent delivery audit. */
@Component
public class CampaignConversionAuditHandler implements OutboxHandler {
    private final InboxGuard inbox;
    private final ObjectMapper json;
    public CampaignConversionAuditHandler(InboxGuard inbox, ObjectMapper json) { this.inbox = inbox; this.json = json; }
    @Override public boolean supports(String type) { return CampaignFlowService.CONVERSION_EVENT.equals(type); }
    @Override public void handle(OutboxEvent event) {
        inbox.once("campaign-report", event, received -> {
            try {
                var data = json.readTree(received.getPayload());
                if (data.path("voucherId").asLong() <= 0 || !java.util.Set.of("CLAIM", "REDEEM", "REFUND").contains(data.path("eventType").asText())) {
                    throw new IllegalArgumentException("转化投递审计数据不合法");
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw new IllegalArgumentException("转化投递审计数据无法解析", exception); }
        });
    }
}
