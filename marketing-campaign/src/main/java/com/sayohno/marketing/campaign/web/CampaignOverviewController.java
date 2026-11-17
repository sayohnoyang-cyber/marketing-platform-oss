package com.sayohno.marketing.campaign.web;

import com.sayohno.marketing.campaign.service.CampaignOverviewService;
import com.sayohno.marketing.common.result.Result;
import com.sayohno.marketing.common.result.Results;
import org.springframework.web.bind.annotation.*;

@RestController
public class CampaignOverviewController {
    private final CampaignOverviewService service;
    public CampaignOverviewController(CampaignOverviewService service) { this.service = service; }
    @GetMapping("/api/merchant/overview")
    public Result<?> overview(@RequestParam(defaultValue = "today") String period, @RequestParam(required = false) Long activityId) {
        return Results.success(service.overview(period, activityId));
    }
}
