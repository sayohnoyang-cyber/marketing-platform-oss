package com.sayohno.marketing.campaign.service;

import com.sayohno.marketing.common.lock.DistributedLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 到期活动库存回收调度器：通过分布式租约锁避免多实例同时重复触发冻结回收。
 */
@Component
public class CampaignRecycleScheduler {
    private static final String LOCK_NAME = "scheduler:campaign-recycle";
    private static final Duration LOCK_TTL = Duration.ofSeconds(50);

    private final CampaignService campaignService;
    private final ObjectProvider<DistributedLock> distributedLock;

    public CampaignRecycleScheduler(CampaignService campaignService) {
        this(campaignService, null);
    }

    @Autowired
    public CampaignRecycleScheduler(CampaignService campaignService, ObjectProvider<DistributedLock> distributedLock) {
        this.campaignService = campaignService;
        this.distributedLock = distributedLock;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void recycleEndedAllocations() {
        DistributedLock lock = distributedLock == null ? null : distributedLock.getIfAvailable();
        if (lock != null) {
            lock.tryExecute(LOCK_NAME, LOCK_TTL, campaignService::recycleDue);
        } else {
            campaignService.recycleDue();
        }
    }
}
