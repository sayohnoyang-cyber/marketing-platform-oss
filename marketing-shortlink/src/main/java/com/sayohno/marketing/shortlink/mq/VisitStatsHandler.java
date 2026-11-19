package com.sayohno.marketing.shortlink.mq;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sayohno.marketing.common.outbox.InboxGuard;
import com.sayohno.marketing.common.outbox.OutboxEvent;
import com.sayohno.marketing.common.outbox.OutboxHandler;
import com.sayohno.marketing.shortlink.dao.entity.LinkAccessLog;
import com.sayohno.marketing.shortlink.dao.entity.LinkStatsDaily;
import com.sayohno.marketing.shortlink.dao.entity.LinkStatsVisitor;
import com.sayohno.marketing.shortlink.dao.mapper.LinkAccessLogMapper;
import com.sayohno.marketing.shortlink.dao.mapper.LinkStatsDailyMapper;
import com.sayohno.marketing.shortlink.dao.mapper.LinkStatsVisitorMapper;
import com.sayohno.marketing.shortlink.service.RedirectService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Component
public class VisitStatsHandler implements OutboxHandler {
    private final InboxGuard inboxGuard;
    private final LinkAccessLogMapper accessLogMapper;
    private final LinkStatsDailyMapper dailyMapper;
    private final LinkStatsVisitorMapper visitorMapper;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<StatsApplyProbe> probe;

    public VisitStatsHandler(InboxGuard inboxGuard,
                             LinkAccessLogMapper accessLogMapper,
                             LinkStatsDailyMapper dailyMapper,
                             LinkStatsVisitorMapper visitorMapper,
                             ObjectMapper objectMapper,
                             ObjectProvider<StatsApplyProbe> probe) {
        this.inboxGuard = inboxGuard;
        this.accessLogMapper = accessLogMapper;
        this.dailyMapper = dailyMapper;
        this.visitorMapper = visitorMapper;
        this.objectMapper = objectMapper;
        this.probe = probe;
    }

    @Override
    public boolean supports(String eventType) {
        return RedirectService.VISIT_EVENT.equals(eventType);
    }

    @Override
    public void handle(OutboxEvent event) {
        inboxGuard.once("shortlink-stats", event, this::apply);
    }

    private void apply(OutboxEvent event) {
        JsonNode payload;
        try {
            payload = objectMapper.readTree(event.getPayload());
        } catch (Exception exception) {
            throw new IllegalArgumentException("统计消息无法解析");
        }
        if (!payload.hasNonNull("occurredAt")) {
            throw new IllegalArgumentException("统计消息缺少发生时间");
        }
        long linkId = payload.path("linkId").asLong();
        String visitorId = payload.path("visitorId").asText("anonymous");
        String channelCode = payload.hasNonNull("channelCode") ? payload.get("channelCode").asText(null) : null;
        LocalDateTime occurredAt = LocalDateTime.parse(payload.get("occurredAt").asText());
        LinkAccessLog access = new LinkAccessLog();
        access.setEventId(event.getEventId());
        access.setLinkId(linkId);
        access.setVisitorId(visitorId);
        access.setChannelCode(channelCode);
        access.setVisitTime(occurredAt);
        accessLogMapper.insert(access);
        LocalDate day = occurredAt.toLocalDate();
        boolean firstVisitor = visitorMapper.selectCount(Wrappers.<LinkStatsVisitor>lambdaQuery()
                .eq(LinkStatsVisitor::getLinkId, linkId)
                .eq(LinkStatsVisitor::getStatDate, day)
                .eq(LinkStatsVisitor::getVisitorId, visitorId)) == 0;
        if (firstVisitor) {
            LinkStatsVisitor visitor = new LinkStatsVisitor();
            visitor.setLinkId(linkId);
            visitor.setStatDate(day);
            visitor.setVisitorId(visitorId);
            visitorMapper.insert(visitor);
        }
        probe.ifAvailable(StatsApplyProbe::beforeDailyAggregate);
        int uvDelta = firstVisitor ? 1 : 0;
        LinkStatsDaily daily = dailyMapper.selectOne(Wrappers.<LinkStatsDaily>lambdaQuery()
                .eq(LinkStatsDaily::getLinkId, linkId)
                .eq(LinkStatsDaily::getStatDate, day));
        if (daily == null) {
            LinkStatsDaily created = new LinkStatsDaily();
            created.setLinkId(linkId);
            created.setStatDate(day);
            created.setPv(1L);
            created.setUv((long) uvDelta);
            created.setUip(0L);
            try {
                dailyMapper.insert(created);
                return;
            } catch (DataIntegrityViolationException exception) {
                daily = dailyMapper.selectOne(Wrappers.<LinkStatsDaily>lambdaQuery()
                        .eq(LinkStatsDaily::getLinkId, linkId)
                        .eq(LinkStatsDaily::getStatDate, day));
            }
        }
        if (daily == null) {
            throw new IllegalStateException("日统计行不存在");
        }
        dailyMapper.add(daily.getId(), uvDelta, LocalDateTime.now());
    }
}
