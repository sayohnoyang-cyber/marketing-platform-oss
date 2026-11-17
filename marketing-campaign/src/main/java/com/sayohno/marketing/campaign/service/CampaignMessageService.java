package com.sayohno.marketing.campaign.service;

import com.sayohno.marketing.campaign.dao.mapper.CampaignMessageMapper;
import com.sayohno.marketing.common.config.MarketingProperties;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class CampaignMessageService {
    private final CampaignService campaigns;
    private final CampaignMessageMapper mapper;
    private final MarketingProperties properties;
    private final Clock clock;

    public CampaignMessageService(CampaignService campaigns, CampaignMessageMapper mapper, MarketingProperties properties, Clock clock) {
        this.campaigns = campaigns; this.mapper = mapper; this.properties = properties; this.clock = clock;
    }

    public Map<String, Object> status(long activityId) {
        campaigns.requireOwned(activityId);
        LocalDateTime now = LocalDateTime.now(clock);
        var sample = mapper.sample(activityId, now.minusMinutes(5), now.minus(properties.getOutbox().getSendingLease()));
        long backlog = sample.getPending() + sample.getSending() + sample.getAwaitingReceipt();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("transport", properties.getMq().isEnabled() ? "ROCKETMQ" : "LOCAL");
        result.put("publisherEnabled", properties.getOutbox().isPublishEnabled());
        result.put("status", sample.getDead() > 0 || sample.getStalled() > 0 ? "FAILED" : backlog > 0 ? "BACKLOG" : sample.getTotal() == 0 ? "IDLE" : "HEALTHY");
        result.put("pending", sample.getPending()); result.put("sending", sample.getSending()); result.put("dead", sample.getDead());
        result.put("retrying", sample.getRetrying()); result.put("awaitingReceipt", sample.getAwaitingReceipt()); result.put("backlog", backlog);
        result.put("completedInWindow", sample.getCompleted()); result.put("windowSeconds", 300);
        result.put("observedPerSecond", BigDecimal.valueOf(sample.getCompleted()).divide(BigDecimal.valueOf(300), 3, RoundingMode.HALF_UP));
        result.put("estimatedDrainSeconds", sample.getCompleted() == 0 ? null : (long) Math.ceil(backlog * 300.0 / sample.getCompleted()));
        result.put("oldestPendingSeconds", sample.getOldestPending() == null ? null : Math.max(0, Duration.between(sample.getOldestPending(), now).getSeconds()));
        result.put("lastConsumedAt", sample.getLastConsumedAt()); result.put("generatedAt", now); result.put("capacityVerified", false);
        result.put("definition", "仅当前活动的应用Outbox与全部预期消费者回执。近期处理量按事件去重；不是RocketMQ Broker队列深度或最大消费容量。排空估算使用最近5分钟平均处理速度。");
        return result;
    }
}
