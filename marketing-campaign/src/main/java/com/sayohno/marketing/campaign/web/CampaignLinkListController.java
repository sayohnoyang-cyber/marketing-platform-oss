package com.sayohno.marketing.campaign.web;

import com.sayohno.marketing.campaign.service.CampaignLinkListService;
import com.sayohno.marketing.common.result.Result;
import com.sayohno.marketing.common.result.Results;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/merchant/link-directory")
public class CampaignLinkListController {
    private final CampaignLinkListService lists;
    public CampaignLinkListController(CampaignLinkListService lists) { this.lists = lists; }

    @GetMapping
    public Result<?> page(@RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") long size,
                          @RequestParam(required = false) String keyword, @RequestParam(required = false) Long activityId,
                          @RequestParam(defaultValue = "false") boolean unassigned, @RequestParam(defaultValue = "ALL") String state) {
        return Results.success(lists.page(page, size, keyword, activityId, unassigned, state));
    }

    @GetMapping("/activities")
    public Result<?> options(@RequestParam(required = false) String keyword, @RequestParam(defaultValue = "1") long page) {
        return Results.success(lists.options(keyword, page));
    }

    @GetMapping("/spotlight")
    public Result<?> spotlight() { return Results.success(lists.spotlight()); }
}
