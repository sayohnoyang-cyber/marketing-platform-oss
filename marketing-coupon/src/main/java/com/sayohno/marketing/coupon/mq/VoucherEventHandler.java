package com.sayohno.marketing.coupon.mq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sayohno.marketing.common.outbox.InboxGuard;
import com.sayohno.marketing.common.outbox.OutboxEvent;
import com.sayohno.marketing.common.outbox.OutboxHandler;
import com.sayohno.marketing.coupon.service.VoucherClaimService;
import com.sayohno.marketing.coupon.service.VoucherRedemptionService;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class VoucherEventHandler implements OutboxHandler {
    private static final Set<String> TYPES = Set.of(
            VoucherClaimService.CLAIM_EVENT,
            VoucherClaimService.ASYNC_CLAIM_EVENT,
            VoucherRedemptionService.REDEEM_EVENT,
            VoucherRedemptionService.REFUND_EVENT
    );
    private final InboxGuard inboxGuard;
    private final VoucherClaimService claimService;
    private final ObjectMapper objectMapper;

    public VoucherEventHandler(InboxGuard inboxGuard, VoucherClaimService claimService, ObjectMapper objectMapper) {
        this.inboxGuard = inboxGuard;
        this.claimService = claimService;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String eventType) {
        return TYPES.contains(eventType);
    }

    @Override
    public void handle(OutboxEvent event) {
        if (VoucherClaimService.ASYNC_CLAIM_EVENT.equals(event.getEventType())) {
            // 异步领券落库必须脱离外层事务执行：内部依赖“独立事务提交后释放 Redis hold”，且瞬时异常需直接抛出重试。
            inboxGuard.onceWithoutOuterTx("coupon-event", event, this::dispatchAsyncClaim);
            return;
        }
        inboxGuard.once("coupon-event", event, received -> {
            try {
                JsonNode payload = objectMapper.readTree(received.getPayload());
                if (payload.path("voucherId").asLong(0) <= 0) {
                    throw new IllegalArgumentException("优惠券事件缺少 voucherId");
                }
            } catch (IllegalArgumentException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalArgumentException("优惠券事件载荷无法解析", exception);
            }
        });
    }

    private void dispatchAsyncClaim(OutboxEvent received) {
        JsonNode payload;
        try {
            payload = objectMapper.readTree(received.getPayload());
        } catch (Exception exception) {
            throw new IllegalArgumentException("优惠券事件载荷无法解析", exception);
        }
        long requestRowId = payload.path("requestRowId").asLong(0);
        String token = payload.path("token").asText("");
        if (requestRowId <= 0) {
            throw new IllegalArgumentException("异步领券事件缺少 requestRowId");
        }
        claimService.completeAsyncClaim(requestRowId, token);
    }
}
