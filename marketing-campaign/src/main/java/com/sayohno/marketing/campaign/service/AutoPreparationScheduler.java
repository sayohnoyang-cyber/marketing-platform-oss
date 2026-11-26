package com.sayohno.marketing.campaign.service;

import com.sayohno.marketing.common.config.MarketingProperties;
import com.sayohno.marketing.common.lock.DistributedLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 开场前自动预热兜底调度器：定时检查未来 5 分钟内即将开场的已发布活动，
 * 若当前配置尚未预热或预热记录已过期，则自动触发一次预热（P2-7）。
 * 通过分布式租约锁保证多实例部署下同一周期仅由一个节点执行。
 */
@Component
public class AutoPreparationScheduler {
    private static final String LOCK_NAME = "scheduler:auto-prepare";
    private static final Duration LOCK_TTL = Duration.ofSeconds(50);

    private final CampaignPreparationService preparationService;
    private final MarketingProperties properties;
    private final ObjectProvider<DistributedLock> distributedLock;

    public AutoPreparationScheduler(CampaignPreparationService preparationService, MarketingProperties properties) {
        this(preparationService, properties, null);
    }

    @Autowired
    public AutoPreparationScheduler(CampaignPreparationService preparationService,
                                    MarketingProperties properties,
                                    ObjectProvider<DistributedLock> distributedLock) {
        this.preparationService = preparationService;
        this.properties = properties;
        this.distributedLock = distributedLock;
    }

    @Scheduled(fixedDelayString = "${AUTO_PREPARE_DELAY:60000}", initialDelayString = "${AUTO_PREPARE_INITIAL_DELAY:60000}")
    public void autoPrepare() {
        if (!properties.getCapacity().isAutoPrepareEnabled()) {
            return;
        }
        DistributedLock lock = distributedLock == null ? null : distributedLock.getIfAvailable();
        if (lock != null) {
            lock.tryExecute(LOCK_NAME, LOCK_TTL, preparationService::autoPrepareUpcoming);
        } else {
            preparationService.autoPrepareUpcoming();
        }
    }
}
