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

    /**
     * 查询单条短链的去重点击访客数（供活动域聚合服务调用，避免跨模块直接依赖 LinkAccessLogMapper）。
     */
    public long uniqueLinkClicks(long linkId) {
        return logs.uniqueLinkClicks(linkId);
    }

    /**
     * 按渠道码分组一次性统计单条短链的去重点击数（空字符串键表示无渠道码的直接跳转）。
     */
    public Map<String, Long> uniqueClicksByChannel(long linkId) {
        Map<String, Long> grouped = new LinkedHashMap<>();
        for (Map<String, Object> row : logs.uniqueClicksGroupedByChannel(linkId)) {
            Object codeObj = row.get("channel_code");
            if (codeObj == null) {
                codeObj = row.get("CHANNEL_CODE");
            }
            Object clicksObj = row.get("clicks");
            if (clicksObj == null) {
                clicksObj = row.get("CLICKS");
            }
            String code = codeObj == null ? "" : String.valueOf(codeObj);
            long clicks = clicksObj instanceof Number n ? n.longValue() : 0L;
            grouped.put(code, clicks);
        }
        return grouped;
    }
}
