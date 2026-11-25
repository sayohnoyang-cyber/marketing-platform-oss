package com.sayohno.marketing.campaign.service;

import com.sayohno.marketing.campaign.ActivityPhase;
import com.sayohno.marketing.campaign.dao.mapper.CampaignLinkListMapper;
import com.sayohno.marketing.campaign.web.dto.ActivitySpotlight;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.common.result.ErrorCode;
import com.sayohno.marketing.identity.service.MerchantAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class CampaignLinkListService {
    private final CampaignLinkListMapper lists;
    private final MerchantAccessService access;
    private final Clock clock;

    public CampaignLinkListService(CampaignLinkListMapper lists, MerchantAccessService access, Clock clock) {
        this.lists = lists; this.access = access; this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> page(long page, long size, String keyword, Long activityId, boolean unassigned, String state) {
        long merchantId = access.requireMerchant().getMerchantId();
        if (!Set.of("ALL", "ACTIVE", "INACTIVE").contains(state) || (activityId != null && (activityId <= 0 || unassigned))) {
            throw new BizException(ErrorCode.VALIDATION, "列表筛选条件不正确");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Map<String, Object> query = new HashMap<>();
        query.put("merchantId", merchantId); query.put("now", now); query.put("word", word(keyword));
        query.put("activityId", activityId); query.put("unassigned", unassigned); query.put("state", state);
        long safeSize = Math.min(100, Math.max(1, size));
        long total = lists.count(query);
        long safePage = Math.min(Math.max(1, page), Math.max(1, (total + safeSize - 1) / safeSize));
        var rows = lists.page(query, (safePage - 1) * safeSize, safeSize);
        // 仅批量读取当前页的落地页到达和短链点击统计；SQL 条数固定，不随活动或渠道数量增长。
        var activityIds = rows.stream().filter(row -> !"PLAIN".equals(row.getKind())).map(row -> row.getActivityId()).distinct().toList();
        var allLinkIds = rows.stream().map(row -> row.getLinkId()).filter(java.util.Objects::nonNull).distinct().toList();
        Map<String, Long> arrivals = new HashMap<>();
        if (!activityIds.isEmpty()) for (var row : lists.entryVisits(merchantId, activityIds)) {
            arrivals.put(row.get("activity_id") + ":" + row.get("channel_id"), count(row, "visits"));
        }
        Map<String, Long> clicks = new HashMap<>();
        if (!allLinkIds.isEmpty()) for (var row : lists.linkClicks(merchantId, allLinkIds)) {
            String ch = row.get("channel_code") == null ? "" : String.valueOf(row.get("channel_code"));
            clicks.put(row.get("id") + ":" + ch, count(row, "clicks"));
        }
        for (var row : rows) {
            if ("PLAIN".equals(row.getKind())) {
                long c = row.getLinkId() == null ? 0L : clicks.getOrDefault(row.getLinkId() + ":", 0L);
                row.setClicks(c);
                row.setVisits(c);
            } else {
                String channelKey = "MAIN".equals(row.getKind()) ? "" : (row.getSource() == null ? "" : row.getSource());
                long c = row.getLinkId() == null ? 0L : clicks.getOrDefault(row.getLinkId() + ":" + channelKey, 0L);
                row.setClicks(c);
                row.setVisits(arrivals.getOrDefault(row.getActivityId() + ":" + row.getChannelId(), 0L));
            }
            if ("PLAIN".equals(row.getKind())) {
                row.setPage(!Boolean.TRUE.equals(row.getOnFlag()) ? "已停用"
                        : row.getValidDate() != null && !now.isBefore(row.getValidDate()) ? "已过期" : "已启用");
            } else {
                var phase = ActivityPhase.of(row.getActivityStatus(), row.getStartTime(), row.getEndTime(), now);
                row.setPhase(phase.name());
                row.setPage(!Boolean.TRUE.equals(row.getOnFlag()) ? "已停用" : !Boolean.TRUE.equals(row.getMainReady()) ? "主链不可用" : phaseLabel(phase));
            }
        }
        var totals = lists.totals(query);
        var rawSegments = lists.jumpSegments(merchantId);
        List<Map<String, Object>> segments = new ArrayList<>();
        long visits = 0, other = 0;
        for (int i = 0; i < rawSegments.size(); i++) {
            var segment = rawSegments.get(i); long value = count(segment, "visits"); visits += value;
            if (i < 5) segments.add(Map.of("id", String.valueOf(segment.get("activity_id")), "name", segment.get("activity_name"), "value", value));
            else other += value;
        }
        if (rawSegments.size() > 5) segments.add(Map.of("id", "OTHER", "name", "其他", "value", other));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", rows); result.put("total", total); result.put("page", safePage); result.put("size", safeSize);
        result.put("summary", Map.of("total", count(totals, "total"), "enabled", count(totals, "enabled"), "visits", visits, "segments", segments));
        result.put("definition", "顶部为全部短链点击访客（每条短链独立去重后相加）；活动入口列表同时区分短链点击（302跳转去重）与落地页到达（有效访问去重），普通短链仅统计跳转点击。统计可能存在异步延迟。");
        return result;
    }

    public Map<String, Object> options(String keyword, long page) {
        long merchantId = access.requireMerchant().getMerchantId();
        long safePage = Math.min(1_000_000, Math.max(1, page));
        var rows = lists.options(merchantId, word(keyword), (safePage - 1) * 20, 21);
        return Map.of("records", rows.subList(0, Math.min(20, rows.size())), "hasMore", rows.size() > 20, "page", safePage);
    }

    public ActivitySpotlight spotlight() {
        var now = LocalDateTime.now(clock);
        var row = lists.spotlight(access.requireMerchant().getMerchantId(), now);
        if (row != null) row.setPhase(ActivityPhase.of(row.getStatus(), row.getStartTime(), row.getEndTime(), now).name());
        return row;
    }

    private String word(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.length() > 128) throw new BizException(ErrorCode.VALIDATION, "搜索内容最多128个字");
        return "%" + value.trim().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    private long count(Map<String, Object> row, String key) { return row.get(key) instanceof Number n ? n.longValue() : 0; }
    private String phaseLabel(ActivityPhase phase) {
        return switch (phase) {
            case DRAFT -> "草稿"; case WARMUP -> "预热中"; case LIVE -> "进行中";
            case ENDED -> "已结束"; case PAUSED -> "已暂停"; case OFFLINE -> "已下线"; case CANCELLED -> "已终止";
        };
    }
}
