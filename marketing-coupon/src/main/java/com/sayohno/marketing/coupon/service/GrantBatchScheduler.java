package com.sayohno.marketing.coupon.service;

import com.sayohno.marketing.common.config.MarketingProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

@Component
public class GrantBatchScheduler {
    private final GrantBatchService taskService;
    private final MarketingProperties properties;
    private final Executor grantBatchExecutor;

    public GrantBatchScheduler(GrantBatchService taskService,
                               MarketingProperties properties,
                               @Qualifier("grantBatchExecutor") Executor grantBatchExecutor) {
        this.taskService = taskService;
        this.properties = properties;
        this.grantBatchExecutor = grantBatchExecutor;
    }

    @Scheduled(fixedDelayString = "${OFFER_GRANT_BATCH_SCAN_DELAY:5000}")
    public void scan() {
        if (properties.getOffer().isGrantBatchScanEnabled()) {
            // 通过独立线程池异步执行批量发券，保证 OutboxPublisher 等调度器不被阻塞。
            taskService.scanDueAsync(grantBatchExecutor);
        }
    }
}
