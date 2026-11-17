package com.sayohno.marketing.shortlink.web;

import com.sayohno.marketing.common.result.Result;
import com.sayohno.marketing.common.result.Results;
import com.sayohno.marketing.shortlink.service.LinkStatisticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LinkStatisticsController {
    private final LinkStatisticsService statistics;
    public LinkStatisticsController(LinkStatisticsService statistics) { this.statistics = statistics; }

    @GetMapping("/api/merchant/links/stats")
    public Result<?> summary() { return Results.success(statistics.summary()); }
}
