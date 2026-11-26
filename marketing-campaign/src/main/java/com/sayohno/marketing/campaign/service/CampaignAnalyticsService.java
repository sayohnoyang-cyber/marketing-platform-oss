package com.sayohno.marketing.campaign.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sayohno.marketing.campaign.ActivityStatuses;
import com.sayohno.marketing.campaign.dao.entity.CampaignActivity;
import com.sayohno.marketing.campaign.dao.entity.CampaignChannel;
import com.sayohno.marketing.campaign.dao.entity.CampaignConversion;
import com.sayohno.marketing.campaign.dao.mapper.ActivityCountRow;
import com.sayohno.marketing.campaign.dao.mapper.CampaignChannelMapper;
import com.sayohno.marketing.campaign.dao.mapper.CampaignConversionMapper;
import com.sayohno.marketing.campaign.dao.mapper.CampaignReservationMapper;
import com.sayohno.marketing.campaign.dao.mapper.CampaignVisitMapper;
import com.sayohno.marketing.campaign.dao.mapper.ChannelCountRow;
import com.sayohno.marketing.campaign.dao.mapper.ChannelEventCountRow;
import com.sayohno.marketing.campaign.dao.mapper.ChannelRefundSummaryRow;
import com.sayohno.marketing.campaign.web.dto.CampaignFunnelView;
import com.sayohno.marketing.campaign.web.dto.ChannelStatsView;
import com.sayohno.marketing.shortlink.service.LinkStatisticsService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 活动运营报表、漏斗看板与复盘统计服务：
 * 所有多渠道统计均通过按 channel_id 分组的常数次批量聚合查询完成，彻底消除渠道维度的 N+1 查询风暴。
 */
@Service
public class CampaignAnalyticsService {
    private final CampaignService campaignService;
    private final CampaignChannelMapper channelMapper;
    private final CampaignVisitMapper visitMapper;
    private final CampaignReservationMapper reservationMapper;
    private final CampaignConversionMapper conversionMapper;
    private final LinkStatisticsService linkStatisticsService;
    private final Clock clock;

    public CampaignAnalyticsService(CampaignService campaignService,
                                    CampaignChannelMapper channelMapper,
                                    CampaignVisitMapper visitMapper,
                                    CampaignReservationMapper reservationMapper,
                                    CampaignConversionMapper conversionMapper,
                                    LinkStatisticsService linkStatisticsService,
                                    Clock clock) {
        this.campaignService = campaignService;
        this.channelMapper = channelMapper;
        this.visitMapper = visitMapper;
        this.reservationMapper = reservationMapper;
        this.conversionMapper = conversionMapper;
        this.linkStatisticsService = linkStatisticsService;
        this.clock = clock;
    }

    /**
     * 批量查询并组装活动列表与统计字段，保持固定常数次批量查询。
     */
    public List<Map<String, Object>> listActivitiesSummary() {
        List<CampaignActivity> activities = campaignService.list();
        if (activities.isEmpty()) {
            return List.of();
        }
        List<Long> activityIds = activities.stream().map(CampaignActivity::getId).toList();
        Map<Long, List<CampaignChannel>> channelsByActivity = channelMapper.selectList(Wrappers.<CampaignChannel>lambdaQuery()
                        .in(CampaignChannel::getActivityId, activityIds))
                .stream().collect(Collectors.groupingBy(CampaignChannel::getActivityId));
        Map<Long, Long> reservationCounts = toActivityCountMap(reservationMapper.countByActivityBatch(activityIds, ActivityStatuses.RESERVED));
        Map<Long, Long> visitCounts = toActivityCountMap(visitMapper.uniqueActivityVisitorsBatch(activityIds));
        Map<Long, Long> claimCounts = toActivityCountMap(conversionMapper.countByTypeBatch(activityIds, "CLAIM"));

        List<Map<String, Object>> rows = campaignService.presentBatch(activities, reservationCounts);
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            List<CampaignChannel> channels = channelsByActivity.getOrDefault(id, List.of());
            long enabledChannels = channels.stream().filter(ch -> ch.getStatus() != null && ch.getStatus() == 1).count();
            row.put("channelCount", channels.size());
            row.put("enabledChannels", enabledChannels);
            // 活动直达页默认可用，因此总入口数至少包含 1 个直达入口及已启用渠道数。
            row.put("entryCount", 1 + enabledChannels);
            row.put("visits", visitCounts.getOrDefault(id, 0L));
            row.put("claims", claimCounts.getOrDefault(id, 0L));
            row.put("reservations", reservationCounts.getOrDefault(id, 0L));
        }
        return rows;
    }

    /**
     * 渠道明细报表（强类型返回）：固定 5 次分组聚合查询即可覆盖全部渠道与直达入口，消除按渠道循环 N+1。
     */
    public List<ChannelStatsView> getChannelReports(long activityId) {
        CampaignActivity activity = campaignService.requireOwned(activityId);
        List<CampaignChannel> channels = channelMapper.selectList(Wrappers.<CampaignChannel>lambdaQuery()
                .eq(CampaignChannel::getActivityId, activityId));
        Long mainLinkId = activity.getMainLinkId();

        Map<String, Long> clicksByChannel = mainLinkId == null ? Map.of() : linkStatisticsService.uniqueClicksByChannel(mainLinkId);
        Map<Long, Long> visitsByChannel = toChannelCountMap(visitMapper.uniqueVisitorsByChannel(activityId));
        Map<Long, Map<String, Long>> conversionsByChannel = groupConversionsByChannel(conversionMapper.countByChannelAndType(activityId));
        Map<Long, ChannelRefundSummaryRow> refundsByChannel = groupRefundsByChannel(conversionMapper.refundsByChannel(activityId));
        Map<Long, Long> reservationsByChannel = toChannelCountMap(reservationMapper.countByChannel(activityId, ActivityStatuses.RESERVED));

        List<ChannelStatsView> rows = new ArrayList<>(channels.size() + 1);
        for (CampaignChannel channel : channels) {
            boolean enabled = channel.getStatus() != null && channel.getStatus() == 1;
            rows.add(buildChannelRow(
                    channel.getId(), channel.getChannelCode(), channel.getName(), "CHANNEL", enabled,
                    clicksByChannel, visitsByChannel, conversionsByChannel, refundsByChannel, reservationsByChannel));
        }
        rows.add(buildChannelRow(
                null, null, "直接访问", "DIRECT", true,
                clicksByChannel, visitsByChannel, conversionsByChannel, refundsByChannel, reservationsByChannel));
        return rows;
    }

    public List<Map<String, Object>> report(long activityId) {
        return getChannelReports(activityId).stream().map(ChannelStatsView::toMap).toList();
    }

    public long attributedClaims(long activityId) {
        campaignService.requireOwned(activityId);
        return countActivity(activityId, "CLAIM");
    }

    public long effectiveVisitors(long activityId) {
        campaignService.requireOwned(activityId);
        return visitMapper.uniqueActivityVisitors(activityId);
    }

    public long unreservedActiveVisitors(long activityId, List<Long> activeChannelIds) {
        campaignService.requireOwned(activityId);
        return visitMapper.unreservedActiveVisitors(activityId, activeChannelIds);
    }

    public Map<String, Object> dashboard(long activityId) {
        return board(activityId, "dashboard");
    }

    public Map<String, Object> review(long activityId) {
        Map<String, Object> body = board(activityId, "review");
        body.put("redeemRateNote", "核销率的分子是当前已核销（未退款）的去重券数，分母是本场已领取张数；退款后再次核销不会重复计入核销率。");
        return body;
    }

    private Map<String, Object> board(long activityId, String kind) {
        CampaignActivity activity = campaignService.requireOwned(activityId);
        Map<String, Object> summary = campaignService.present(activity);
        List<ChannelStatsView> channelViews = getChannelReports(activityId);
        long attributed = channelViews.stream().mapToLong(ChannelStatsView::claims).sum();
        Object claimed = summary.get("claimed");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", kind);
        body.put("generatedAt", LocalDateTime.now(clock).toString());
        body.put("timezone", "Asia/Shanghai");
        body.put("activity", summary);
        body.put("channels", channelViews.stream().map(ChannelStatsView::toMap).toList());
        body.put("attributedClaims", attributed);
        body.put("reconciled", claimed instanceof Number number && number.longValue() == attributed);
        addRefundSummary(body, activityId);

        long clicks = activity.getMainLinkId() == null ? 0L : linkStatisticsService.uniqueLinkClicks(activity.getMainLinkId());
        long visits = visitMapper.uniqueActivityVisitors(activityId);
        Long resCount = reservationMapper.selectCount(Wrappers.<com.sayohno.marketing.campaign.dao.entity.CampaignReservation>lambdaQuery()
                .eq(com.sayohno.marketing.campaign.dao.entity.CampaignReservation::getActivityId, activityId)
                .eq(com.sayohno.marketing.campaign.dao.entity.CampaignReservation::getStatus, ActivityStatuses.RESERVED));
        long reservations = resCount == null ? 0L : resCount;
        long redeems = channelViews.stream().mapToLong(ChannelStatsView::redeems).sum();
        long netRedeemed = conversionMapper.countNetRedeemedVouchers(activityId);
        long refunds = channelViews.stream().mapToLong(ChannelStatsView::refunds).sum();

        body.put("clicks", clicks);
        body.put("visits", visits);
        body.put("funnel", new CampaignFunnelView(clicks, visits, reservations, attributed, redeems, netRedeemed, refunds).toMap());
        body.put("visitDefinition", "短链点击为 302 跳转去重访客，落地页到达为有效入口去重访客；直接打开落地页不经过短链时，到达可能大于点击。各渠道独立去重，不直接相加作为活动总数。");
        if (claimed instanceof Number number && number.longValue() > 0) {
            // 核销率分子采用当前仍处于已核销状态的去重券数，确保退款后重核不超过 100%。
            long validRedeemed = Math.min(netRedeemed, number.longValue());
            body.put("redeemRate", BigDecimal.valueOf(validRedeemed).divide(BigDecimal.valueOf(number.longValue()), 4, RoundingMode.HALF_UP));
        } else {
            body.put("redeemRate", null);
        }
        body.put("redeemRateNote", "分子是当前已核销（未退款）的去重券数，分母是本场已领取张数，截止时间为 generatedAt。");
        return body;
    }

    private ChannelStatsView buildChannelRow(Long channelId, String channelCode, String name, String kind, boolean enabled,
                                             Map<String, Long> clicksByChannel,
                                             Map<Long, Long> visitsByChannel,
                                             Map<Long, Map<String, Long>> conversionsByChannel,
                                             Map<Long, ChannelRefundSummaryRow> refundsByChannel,
                                             Map<Long, Long> reservationsByChannel) {
        Map<String, Long> conv = conversionsByChannel.getOrDefault(channelId, Map.of());
        ChannelRefundSummaryRow refundRow = refundsByChannel.get(channelId);
        long refundCount = refundRow == null ? 0L : refundRow.getRefundCount();
        long amountCount = refundRow == null ? 0L : refundRow.getAmountCount();
        BigDecimal knownAmount = refundRow == null || refundRow.getKnownAmount() == null
                ? BigDecimal.ZERO : refundRow.getKnownAmount();
        long missing = refundCount - amountCount;
        return new ChannelStatsView(
                channelId,
                name,
                kind,
                enabled,
                clicksByChannel.getOrDefault(channelCode == null ? "" : channelCode, 0L),
                visitsByChannel.getOrDefault(channelId, 0L),
                conv.getOrDefault("CLAIM", 0L),
                conv.getOrDefault("REDEEM", 0L),
                conv.getOrDefault("REFUND", 0L),
                missing == 0 ? knownAmount : null,
                knownAmount,
                missing,
                missing == 0 ? "COMPLETE" : "PARTIAL",
                reservationsByChannel.getOrDefault(channelId, 0L)
        );
    }

    private void addRefundSummary(Map<String, Object> target, long activityId) {
        var refund = conversionMapper.refunds(activityId, null, false);
        long missing = refund.getRefundCount() - refund.getAmountCount();
        target.put("refundAmount", missing == 0 ? refund.getKnownAmount() : null);
        target.put("knownRefundAmount", refund.getKnownAmount());
        target.put("unreportedRefunds", missing);
        target.put("refundAmountStatus", missing == 0 ? "COMPLETE" : "PARTIAL");
    }

    private long countActivity(long activityId, String type) {
        Long count = conversionMapper.selectCount(Wrappers.<CampaignConversion>lambdaQuery()
                .eq(CampaignConversion::getActivityId, activityId)
                .eq(CampaignConversion::getEventType, type));
        return count == null ? 0 : count;
    }

    private static Map<Long, Long> toActivityCountMap(List<ActivityCountRow> rows) {
        Map<Long, Long> result = new LinkedHashMap<>();
        for (var row : rows) {
            if (row.getActivityId() != null && row.getCnt() != null) {
                result.put(row.getActivityId(), row.getCnt());
            }
        }
        return result;
    }

    private static Map<Long, Long> toChannelCountMap(List<ChannelCountRow> rows) {
        // 使用 HashMap 允许 channelId=null 作为“直接访问”分组键
        Map<Long, Long> result = new HashMap<>();
        for (ChannelCountRow row : rows) {
            if (row.getCnt() != null) {
                result.put(row.getChannelId(), row.getCnt());
            }
        }
        return result;
    }

    private static Map<Long, Map<String, Long>> groupConversionsByChannel(List<ChannelEventCountRow> rows) {
        Map<Long, Map<String, Long>> result = new HashMap<>();
        for (ChannelEventCountRow row : rows) {
            if (row.getEventType() != null && row.getCnt() != null) {
                result.computeIfAbsent(row.getChannelId(), ignored -> new HashMap<>())
                        .put(row.getEventType(), row.getCnt());
            }
        }
        return result;
    }

    private static Map<Long, ChannelRefundSummaryRow> groupRefundsByChannel(List<ChannelRefundSummaryRow> rows) {
        Map<Long, ChannelRefundSummaryRow> result = new HashMap<>();
        for (ChannelRefundSummaryRow row : rows) {
            result.put(row.getChannelId(), row);
        }
        return result;
    }
}
