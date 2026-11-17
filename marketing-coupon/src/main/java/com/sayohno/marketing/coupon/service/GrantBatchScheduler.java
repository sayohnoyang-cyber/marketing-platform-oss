package com.sayohno.marketing.coupon.service;

import com.sayohno.marketing.common.config.MarketingProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class GrantBatchScheduler {
    private final GrantBatchService taskService;
    private final MarketingProperties properties;

    public GrantBatchScheduler(GrantBatchService taskService, MarketingProperties properties) {
        this.taskService = taskService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${OFFER_GRANT_BATCH_SCAN_DELAY:5000}")
    public void scan() {
        if (properties.getOffer().isGrantBatchScanEnabled()) {
            taskService.scanDue();
        }
    }
}
