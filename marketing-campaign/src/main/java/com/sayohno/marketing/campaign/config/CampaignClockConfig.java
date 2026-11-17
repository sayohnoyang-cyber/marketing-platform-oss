package com.sayohno.marketing.campaign.config;

import com.sayohno.marketing.campaign.service.CampaignService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class CampaignClockConfig {
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock campaignClock() {
        return CampaignService.shanghaiClock();
    }
}
