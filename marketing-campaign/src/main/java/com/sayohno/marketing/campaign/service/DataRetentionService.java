package com.sayohno.marketing.campaign.service;

import com.sayohno.marketing.campaign.dao.mapper.CampaignVisitMapper;
import com.sayohno.marketing.common.config.MarketingProperties;
import com.sayohno.marketing.common.lock.DistributedLock;
import com.sayohno.marketing.common.outbox.ConsumerInboxMapper;
import com.sayohno.marketing.common.outbox.OutboxEventMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * 历史大表定期归档/清理服务（P3-1）：
 * 清理已投递完成的 Outbox 事件、已过期 Inbox 去重记录，以及超过保留期（至少 90 天以保护历史到场率校准）且未被归因引用的访问记录。
 * 采用分布式锁互斥 + 分批（LIMIT 1000）按主键清理，避免单条无界 DELETE 造成锁表、主从延迟和 Undo Log 膨胀。
 */
@Service
public class DataRetentionService {
    private static final Duration MIN_VISIT_RETENTION = Duration.ofDays(90);
    private static final String LOCK_NAME = "scheduler:data-retention";
    private static final Duration LOCK_TTL = Duration.ofMinutes(10);
    private static final int PURGE_BATCH_SIZE = 1000;
    private static final int MAX_BATCHES_PER_TABLE = 50;

    private final OutboxEventMapper outboxMapper;
    private final ConsumerInboxMapper inboxMapper;
    private final CampaignVisitMapper visitMapper;
    private final MarketingProperties properties;
    private final Clock clock;
    private final ObjectProvider<DistributedLock> distributedLock;

    public DataRetentionService(OutboxEventMapper outboxMapper,
                                ConsumerInboxMapper inboxMapper,
                                CampaignVisitMapper visitMapper,
                                MarketingProperties properties,
                                Clock clock) {
        this(outboxMapper, inboxMapper, visitMapper, properties, clock, null);
    }

    @Autowired
    public DataRetentionService(OutboxEventMapper outboxMapper,
                                ConsumerInboxMapper inboxMapper,
                                CampaignVisitMapper visitMapper,
                                MarketingProperties properties,
                                Clock clock,
                                ObjectProvider<DistributedLock> distributedLock) {
        this.outboxMapper = outboxMapper;
        this.inboxMapper = inboxMapper;
        this.visitMapper = visitMapper;
        this.properties = properties;
        this.clock = clock;
        this.distributedLock = distributedLock;
    }

    @Scheduled(cron = "${RETENTION_CRON:0 30 3 * * ?}")
    public void scheduledPurge() {
        DistributedLock lock = distributedLock == null ? null : distributedLock.getIfAvailable();
        if (lock != null) {
            lock.tryExecute(LOCK_NAME, LOCK_TTL, () -> purgeExpired(LocalDateTime.now(clock)));
        } else {
            purgeExpired(LocalDateTime.now(clock));
        }
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

        int deletedOutbox = deleteInBatches(
                limit -> outboxMapper.findExpiredSentIds(outboxCutoff, limit),
                outboxMapper::deleteBatchIds);
        int deletedInbox = deleteInBatches(
                limit -> inboxMapper.findExpiredIds(outboxCutoff, limit),
                inboxMapper::deleteBatchIds);
        int deletedVisits = deleteInBatches(
                limit -> visitMapper.findExpiredUnreferencedIds(visitCutoff, limit),
                visitMapper::deleteBatchIds);

        Map<String, Integer> summary = new LinkedHashMap<>();
        summary.put("outboxDeleted", deletedOutbox);
        summary.put("inboxDeleted", deletedInbox);
        summary.put("visitsDeleted", deletedVisits);
        return summary;
    }

    private int deleteInBatches(IntFunction<List<Long>> idFetcher, Function<List<Long>, Integer> batchDeleter) {
        int totalDeleted = 0;
        for (int round = 0; round < MAX_BATCHES_PER_TABLE; round++) {
            List<Long> ids = idFetcher.apply(PURGE_BATCH_SIZE);
            if (ids == null || ids.isEmpty()) {
                break;
            }
            Integer deleted = batchDeleter.apply(ids);
            int affected = deleted == null ? 0 : deleted;
            totalDeleted += affected;
            if (ids.size() < PURGE_BATCH_SIZE || affected == 0) {
                break;
            }
        }
        return totalDeleted;
    }
}
