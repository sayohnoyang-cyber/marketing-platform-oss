package com.sayohno.marketing.campaign.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sayohno.marketing.campaign.ActivityStatuses;
import com.sayohno.marketing.campaign.dao.entity.CampaignActivity;
import com.sayohno.marketing.campaign.dao.mapper.CampaignActivityMapper;
import com.sayohno.marketing.campaign.dao.mapper.CampaignAttendanceMapper;
import com.sayohno.marketing.campaign.dao.mapper.CampaignChannelMapper;
import com.sayohno.marketing.campaign.dao.entity.CampaignChannel;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CampaignCalibrationService {
    private static final int MIN_SAMPLES = 20;
    private final CampaignService campaigns;
    private final CampaignActivityMapper activities;
    private final CampaignAttendanceMapper attendance;
    private final Clock clock;
    private final CampaignChannelMapper channels;

    public CampaignCalibrationService(CampaignService campaigns, CampaignActivityMapper activities,
                                      CampaignAttendanceMapper attendance, CampaignChannelMapper channels, Clock clock) {
        this.campaigns = campaigns; this.activities = activities; this.attendance = attendance; this.channels = channels; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> recommend(long activityId) {
        var current = campaigns.requireOwned(activityId);
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime cutoff = now.minusSeconds(10);
        if (current.getStartTime().isBefore(cutoff)) cutoff = current.getStartTime();
        var history = activities.selectList(Wrappers.<CampaignActivity>lambdaQuery()
                .eq(CampaignActivity::getMerchantId, current.getMerchantId())
                .eq(CampaignActivity::getActivityType, current.getActivityType())
                .eq(CampaignActivity::getStatus, ActivityStatuses.PUBLISHED)
                .ne(CampaignActivity::getId, activityId)
                .ge(CampaignActivity::getStartTime, now.minusDays(90))
                .le(CampaignActivity::getStartTime, cutoff)
                .orderByDesc(CampaignActivity::getStartTime).last("LIMIT 20"));
        Map<String, long[]> cohorts = new HashMap<>();
        long total = 0, arrived = 0;
        Set<Long> sampledActivities = new LinkedHashSet<>();
        for (var past : history) {
            for (var sample : attendance.opening(past.getId(), past.getStartTime(), past.getStartTime().plusSeconds(10))) {
                if (sample.getReservations() == 0) continue;
                long[] cohort = cohorts.computeIfAbsent(key(sample.getChannelId(), sample.getName()), ignored -> new long[2]);
                cohort[0] += sample.getReservations(); cohort[1] += sample.getArrivals();
                total += sample.getReservations(); arrived += sample.getArrivals(); sampledActivities.add(past.getId());
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(recommendation("direct", "直接访问", cohorts.get("direct"), total, arrived));
        for (var channel : campaignsChannels(activityId)) {
            rows.add(recommendation(String.valueOf(channel.getId()), channel.getName(),
                    cohorts.get(key(channel.getId(), channel.getName())), total, arrived));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rows", rows); result.put("sampleCount", total); result.put("arrivedCount", arrived);
        result.put("activityCount", sampledActivities.size()); result.put("activityIds", sampledActivities);
        result.put("minimumSamples", MIN_SAMPLES); result.put("windowSeconds", 10); result.put("lookbackDays", 90);
        result.put("generatedAt", now); result.put("modelVersion", "opening-cohort-v1");
        result.put("definition", "最近90天最多20场同类型已发布活动；开场前有效预约用户在开场10秒内产生有效访问或成功领取视为到场，按用户去重。渠道按名称匹配，样本不足20人使用工作空间同类型活动汇总，再不足则使用默认40%。");
        return result;
    }

    private List<CampaignChannel> campaignsChannels(long id) {
        return channels.selectList(Wrappers.<CampaignChannel>lambdaQuery()
                .eq(CampaignChannel::getActivityId, id).orderByAsc(CampaignChannel::getId));
    }

    private Map<String, Object> recommendation(String key, String name, long[] sample, long total, long arrived) {
        long count = sample == null ? 0 : sample[0], arrivals = sample == null ? 0 : sample[1];
        String source = "CHANNEL_HISTORY";
        if (count < MIN_SAMPLES) { count = total; arrivals = arrived; source = "WORKSPACE_HISTORY"; }
        boolean usable = count >= MIN_SAMPLES;
        double rate = usable ? Math.round(1000.0 * arrivals / count) / 10.0 : 40.0;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", key); row.put("name", name); row.put("rate", rate); row.put("sampleCount", count);
        row.put("arrivedCount", arrivals); row.put("usable", usable); row.put("source", usable ? source : "DEFAULT");
        return row;
    }

    private String key(Long channelId, String name) {
        return channelId == null ? "direct" : "channel:" + String.valueOf(name).trim().toLowerCase(Locale.ROOT);
    }
}
