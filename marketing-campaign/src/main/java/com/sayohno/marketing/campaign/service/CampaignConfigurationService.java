package com.sayohno.marketing.campaign.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sayohno.marketing.campaign.dao.entity.CampaignChannel;
import com.sayohno.marketing.campaign.dao.entity.CampaignOffer;
import com.sayohno.marketing.campaign.dao.mapper.CampaignChannelMapper;
import com.sayohno.marketing.campaign.dao.mapper.CampaignOfferMapper;
import com.sayohno.marketing.common.hash.RequestHashes;
import com.sayohno.marketing.coupon.service.OfferInventoryService;
import com.sayohno.marketing.coupon.service.OfferPlanService;
import org.springframework.stereotype.Service;

@Service
public class CampaignConfigurationService {
    private final CampaignService campaigns;
    private final CampaignOfferMapper coupons;
    private final CampaignChannelMapper channels;
    private final OfferInventoryService allocations;
    private final OfferPlanService templates;

    public CampaignConfigurationService(CampaignService campaigns, CampaignOfferMapper coupons,
            CampaignChannelMapper channels, OfferInventoryService allocations, OfferPlanService templates) {
        this.campaigns = campaigns; this.coupons = coupons; this.channels = channels;
        this.allocations = allocations; this.templates = templates;
    }

    /** Mutable stock/visit counts are deliberately not configuration. */
    public String fingerprint(long activityId) {
        var a = campaigns.requireReadable(activityId);
        StringBuilder value = new StringBuilder().append(a.getVersion()).append('|').append(a.getStatus())
                .append('|').append(a.getStartTime()).append('|').append(a.getEndTime())
                .append('|').append(a.getBudgetAmount()).append('|').append(a.getMainLinkId()).append('|').append(a.getMainEnabled());
        for (var c : coupons.selectList(Wrappers.<CampaignOffer>lambdaQuery().eq(CampaignOffer::getActivityId, activityId).orderByAsc(CampaignOffer::getId))) {
            value.append('|').append(c.getPlanId()).append('|').append(templates.publicOffer(c.getPlanId(), a.getMerchantId()));
            if (c.getInventoryId() != null) {
                var allocation = allocations.find(c.getInventoryId());
                value.append('|').append(allocation.getId()).append(':').append(allocation.getQuantity())
                        .append(':').append(allocation.getMaxClaimsPerUser()).append(':').append(allocation.getStatus());
            }
        }
        for (var channel : channels.selectList(Wrappers.<CampaignChannel>lambdaQuery().eq(CampaignChannel::getActivityId, activityId).orderByAsc(CampaignChannel::getId))) {
            value.append('|').append(channel.getId()).append(':').append(channel.getStatus()).append(':').append(channel.getChannelCode());
        }
        return RequestHashes.sha256(value.toString());
    }
}
