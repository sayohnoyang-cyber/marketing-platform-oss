package com.sayohno.marketing.campaign.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sayohno.marketing.campaign.dao.entity.CampaignActivity;
import com.sayohno.marketing.campaign.dao.entity.CampaignChannel;
import com.sayohno.marketing.campaign.dao.mapper.CampaignChannelMapper;
import com.sayohno.marketing.campaign.web.dto.MainLinkView;
import com.sayohno.marketing.common.config.MarketingProperties;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.common.result.ErrorCode;
import com.sayohno.marketing.shortlink.dao.entity.Link;
import com.sayohno.marketing.shortlink.dao.entity.LinkGroup;
import com.sayohno.marketing.shortlink.service.LinkDirectoryService;
import com.sayohno.marketing.shortlink.service.PublicLinkAddress;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 活动主推广短链与渠道管理服务：负责渠道增改、主短链懒创建与启停同步。
 */
@Service
public class CampaignChannelService {
    private static final char[] ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    private final CampaignService campaignService;
    private final CampaignChannelMapper channelMapper;
    private final LinkDirectoryService linkDirectoryService;
    private final CampaignPageCache pageCache;
    private final MarketingProperties properties;

    public CampaignChannelService(CampaignService campaignService,
                                  CampaignChannelMapper channelMapper,
                                  LinkDirectoryService linkDirectoryService,
                                  CampaignPageCache pageCache,
                                  MarketingProperties properties) {
        this.campaignService = campaignService;
        this.channelMapper = channelMapper;
        this.linkDirectoryService = linkDirectoryService;
        this.pageCache = pageCache;
        this.properties = properties;
    }

    @Transactional
    public CampaignChannel addChannel(long activityId, String name, String idempotencyKey) {
        return addChannel(activityId, name, idempotencyKey, null);
    }

    @Transactional
    public CampaignChannel addChannel(long activityId, String name, String idempotencyKey, String customCode) {
        CampaignActivity activity = campaignService.requireOwned(activityId);
        if (customCode != null && !customCode.matches("[A-Za-z0-9]{2,12}")) {
            throw new BizException(ErrorCode.VALIDATION, "渠道标识须为2到12位字母或数字");
        }
        if (name == null || name.isBlank() || idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION, "渠道名称和幂等键不能为空");
        }
        CampaignChannel existing = channelMapper.selectOne(Wrappers.<CampaignChannel>lambdaQuery()
                .eq(CampaignChannel::getActivityId, activityId)
                .eq(CampaignChannel::getIdempotencyKey, idempotencyKey));
        if (existing != null) {
            if (!name.trim().equals(existing.getName()) || (customCode != null && !customCode.equals(existing.getChannelCode()))) {
                throw new BizException(ErrorCode.CONFLICT, "同一幂等键的渠道参数不一致");
            }
            if (existing.getLinkId() == null) {
                existing.setLinkId(ensureMain(activity).getId());
                channelMapper.updateById(existing);
            }
            return existing;
        }
        CampaignChannel channel = new CampaignChannel();
        channel.setActivityId(activityId);
        channel.setMerchantId(activity.getMerchantId());
        channel.setChannelCode(customCode == null ? randomCode(8) : customCode);
        channel.setName(name.trim());
        channel.setIdempotencyKey(idempotencyKey.trim());
        channel.setStatus(1);
        try {
            channelMapper.insert(channel);
        } catch (DataIntegrityViolationException exception) {
            throw new BizException(ErrorCode.CONFLICT, "渠道标识或创建请求已被占用，请检查后重试");
        }
        channel.setLinkId(ensureMain(activity).getId());
        channelMapper.updateById(channel);
        pageCache.evict(activityId);
        return channel;
    }

    public CampaignChannel setChannelEnabled(long activityId, long channelId, boolean enabled) {
        campaignService.requireOwned(activityId);
        CampaignChannel channel = channelMapper.selectById(channelId);
        if (channel == null || !channel.getActivityId().equals(activityId)) {
            throw new BizException(ErrorCode.NOT_FOUND, "渠道不存在");
        }
        channel.setStatus(enabled ? 1 : 0);
        channelMapper.updateById(channel);
        pageCache.evict(activityId);
        return channel;
    }

    public List<CampaignChannel> channels(long activityId) {
        campaignService.requireOwned(activityId);
        return channelMapper.selectList(Wrappers.<CampaignChannel>lambdaQuery()
                .eq(CampaignChannel::getActivityId, activityId)
                .orderByAsc(CampaignChannel::getId));
    }

    public MainLinkView getMainLink(long activityId) {
        CampaignActivity activity = campaignService.requireOwned(activityId);
        boolean enabled = activity.getMainEnabled() != null && activity.getMainEnabled() == 1;
        if (activity.getMainLinkId() == null) {
            return new MainLinkView(null, enabled, "", "", "");
        }
        Link link = linkDirectoryService.findOwned(activity.getMainLinkId());
        return new MainLinkView(link.getId(), enabled, link.getShortUri(), link.getFullShortUrl(), link.getOriginUrl());
    }

    @Transactional
    public MainLinkView openMain(long activityId) {
        CampaignActivity activity = campaignService.requireOwned(activityId);
        ensureMain(activity);
        return getMainLink(activityId);
    }

    public Link ensureMain(CampaignActivity activity) {
        if (activity.getMainLinkId() != null) {
            return linkDirectoryService.findOwned(activity.getMainLinkId());
        }
        LinkGroup group = linkDirectoryService.listGroups().stream()
                .filter(item -> "活动渠道".equals(item.getName()))
                .findFirst()
                .orElseGet(() -> linkDirectoryService.createGroup("活动渠道"));
        String origin = PublicLinkAddress.base(properties.getShortlink().getPublicBaseUrl(), false, null, null)
                + "/a/" + activity.getId();
        Link link = linkDirectoryService.createLink(group.getGid(), origin, activity.getEndTime(),
                "act:" + activity.getId() + ":main", null, null);
        campaignService.bindMainLink(activity.getId(), link.getId());
        activity.setMainLinkId(link.getId());
        activity.setMainEnabled(1);
        return link;
    }

    private static String randomCode(int size) {
        StringBuilder builder = new StringBuilder();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < size; i++) {
            builder.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return builder.toString();
    }
}
