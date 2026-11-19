package com.sayohno.marketing.campaign.service;

import com.sayohno.marketing.common.config.MarketingProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 开场前自动预热兜底调度器：定时检查未来 5 分钟内即将开场的已发布活动，
 * 若当前配置尚未预热或预热记录已过期，则自动触发一次预热（P2-7）。
 */
@Component
public class AutoPreparationScheduler {
    private final CampaignPreparationService preparationService;
    private final MarketingProperties properties;

    public AutoPreparationScheduler(CampaignPreparationService preparationService, MarketingProperties properties) {
        this.preparationService = preparationService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${AUTO_PREPARE_DELAY:60000}", initialDelayString = "${AUTO_PREPARE_INITIAL_DELAY:60000}")
    public void autoPrepare() {
        if (properties.getCapacity().isAutoPrepareEnabled()) {
            preparationService.autoPrepareUpcoming();
        }
    }
}
