package com.sayohno.marketing.campaign.service;

import com.sayohno.marketing.campaign.dao.mapper.CampaignVisitMapper;
import com.sayohno.marketing.common.config.MarketingProperties;
import com.sayohno.marketing.common.outbox.ConsumerInboxMapper;
import com.sayohno.marketing.common.outbox.OutboxEventMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 历史大表定期归档/清理服务（P3-1）：
 * 清理已投递完成的 Outbox 事件、已过期 Inbox 去重记录，以及超过保留期（至少 90 天以保护历史到场率校准）且未被归因引用的访问记录。
 */
@Service
public class DataRetentionService {
    private static final Duration MIN_VISIT_RETENTION = Duration.ofDays(90);

    private final OutboxEventMapper outboxMapper;
    private final ConsumerInboxMapper inboxMapper;
    private final CampaignVisitMapper visitMapper;
    private final MarketingProperties properties;
    private final Clock clock;

    public DataRetentionService(OutboxEventMapper outboxMapper,
                                ConsumerInboxMapper inboxMapper,
                                CampaignVisitMapper visitMapper,
                                MarketingProperties properties,
                                Clock clock) {
        this.outboxMapper = outboxMapper;
        this.inboxMapper = inboxMapper;
        this.visitMapper = visitMapper;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${RETENTION_CRON:0 30 3 * * ?}")
    public void scheduledPurge() {
        purgeExpired(LocalDateTime.now(clock));
    }

    public Map<String, Integer> purgeExpired(LocalDateTime now) {
        Duration outboxRetention = properties.getOutbox().getRetention();
        if (outboxRetention == null || outboxRetention.isNegative()) {
            outboxRetention = Duration.ofDays(7);
        }
        Duration visitRetention = properties.getOutbox().getVisitRetention();
        if (visitRetention == null || visitRetention.compareTo(MIN_VISIT_RETENTION) < 0) {
            visitRetention = MIN_VISIT_RETENTION;
        }
        LocalDateTime outboxCutoff = now.minus(outboxRetention);
        LocalDateTime visitCutoff = now.minus(visitRetention);

        int deletedOutbox = outboxMapper.deleteExpiredSent(outboxCutoff);
        int deletedInbox = inboxMapper.deleteExpired(outboxCutoff);
        int deletedVisits = visitMapper.deleteExpiredUnreferenced(visitCutoff);

        Map<String, Integer> summary = new LinkedHashMap<>();
        summary.put("outboxDeleted", deletedOutbox);
        summary.put("inboxDeleted", deletedInbox);
        summary.put("visitsDeleted", deletedVisits);
        return summary;
    }
}
