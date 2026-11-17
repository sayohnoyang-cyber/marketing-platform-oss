package com.sayohno.marketing.shortlink.service;

import com.sayohno.marketing.identity.service.MerchantAccessService;
import com.sayohno.marketing.shortlink.dao.mapper.LinkAccessLogMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class LinkStatisticsService {
    private final LinkAccessLogMapper logs;
    private final MerchantAccessService access;

    public LinkStatisticsService(LinkAccessLogMapper logs, MerchantAccessService access) {
        this.logs = logs;
        this.access = access;
    }

    public Map<String, Object> summary() {
        long merchantId = access.requireMerchant().getMerchantId();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("links", logs.byMerchant(merchantId));
        result.put("generatedAt", LocalDateTime.now());
        result.put("definition", "短链跳转按浏览器访客去重，跨短链分别计数；常见爬虫不计入。异步统计可能延迟，不等同于活动落地页到达。");
        return result;
    }
}
