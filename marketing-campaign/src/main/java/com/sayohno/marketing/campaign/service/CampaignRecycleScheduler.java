package com.sayohno.marketing.campaign.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CampaignRecycleScheduler {
    private final CampaignService campaignService;

    public CampaignRecycleScheduler(CampaignService campaignService) {
        this.campaignService = campaignService;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void recycleEndedAllocations() {
        campaignService.recycleDue();
    }
}
