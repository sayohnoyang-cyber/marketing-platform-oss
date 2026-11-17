package com.sayohno.marketing.campaign.mq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sayohno.marketing.campaign.service.CampaignFlowService;
import com.sayohno.marketing.common.outbox.InboxGuard;
import com.sayohno.marketing.common.outbox.OutboxEvent;
import com.sayohno.marketing.common.outbox.OutboxHandler;
import com.sayohno.marketing.coupon.service.VoucherClaimService;
import com.sayohno.marketing.coupon.service.VoucherRedemptionService;
import org.springframework.stereotype.Component;

@Component
public class CampaignConversionHandler implements OutboxHandler {
    private final InboxGuard inboxGuard;
    private final CampaignFlowService flowService;
    private final ObjectMapper objectMapper;

    public CampaignConversionHandler(InboxGuard inboxGuard, CampaignFlowService flowService, ObjectMapper objectMapper) {
        this.inboxGuard = inboxGuard;
        this.flowService = flowService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String eventType) {
        return VoucherClaimService.CLAIM_EVENT.equals(eventType)
                || VoucherRedemptionService.REDEEM_EVENT.equals(eventType)
                || VoucherRedemptionService.REFUND_EVENT.equals(eventType);
    }

    @Override
    public void handle(OutboxEvent event) {
        inboxGuard.once("campaign-conversion", event, this::apply);
    }

    private void apply(OutboxEvent event) {
        try {
            JsonNode payload = objectMapper.readTree(event.getPayload());
            long voucherId = payload.path("voucherId").asLong(0);
            if (voucherId == 0) {
                return;
            }
            if (VoucherClaimService.CLAIM_EVENT.equals(event.getEventType())) {
                flowService.completeClaimAttribution(voucherId);
                return;
            }
            Long settlementId = payload.hasNonNull("settlementId") ? payload.get("settlementId").asLong() : null;
            flowService.recordFromCouponEvent(event.getEventType(), voucherId, settlementId);
        } catch (Exception exception) {
            throw new IllegalArgumentException("转化消息无法解析", exception);
        }
    }
}
