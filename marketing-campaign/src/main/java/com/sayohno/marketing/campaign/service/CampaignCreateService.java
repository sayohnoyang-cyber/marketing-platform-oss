package com.sayohno.marketing.campaign.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sayohno.marketing.campaign.dao.entity.CampaignActivity;
import com.sayohno.marketing.campaign.dao.entity.CampaignCreateRequest;
import com.sayohno.marketing.campaign.dao.mapper.CampaignCreateRequestMapper;
import com.sayohno.marketing.campaign.web.dto.ActivityRequest;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.common.hash.RequestHashes;
import com.sayohno.marketing.common.result.ErrorCode;
import com.sayohno.marketing.identity.service.MerchantAccessService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CampaignCreateService {
    private final CampaignService campaigns;
    private final CampaignCreateRequestMapper requests;
    private final MerchantAccessService access;
    private final ObjectMapper json;

    public CampaignCreateService(CampaignService campaigns, CampaignCreateRequestMapper requests,
                                 MerchantAccessService access, ObjectMapper json) {
        this.campaigns = campaigns;
        this.requests = requests;
        this.access = access;
        this.json = json;
    }

    @Transactional
    public CampaignActivity create(ActivityRequest request) {
        long merchantId = access.requireMerchant().getMerchantId();
        String key = request.getIdempotencyKey();
        if (key == null) return insert(request); // 保留旧调用方兼容，新创建页始终传入请求编号。
        String hash;
        try { hash = RequestHashes.sha256(json.writeValueAsString(request)); }
        catch (JsonProcessingException exception) { throw new BizException(ErrorCode.VALIDATION, "活动参数无法读取"); }
        CampaignCreateRequest row = new CampaignCreateRequest();
        row.setMerchantId(merchantId); row.setIdempotencyKey(key); row.setRequestHash(hash);
        try { requests.insert(row); }
        catch (DataIntegrityViolationException ignored) { /* 同一请求的并发重试由下面的行锁串行处理。 */ }
        var locked = requests.lockByKey(merchantId, key);
        if (locked == null || !hash.equals(locked.getRequestHash())) {
            throw new BizException(ErrorCode.CONFLICT, "同一创建请求的活动参数不一致");
        }
        if (locked.getActivityId() != null) return campaigns.requireOwned(locked.getActivityId());
        // 创建结果和请求编号同事务提交，避免活动已保存而客户端未收到响应后重复创建。
        var activity = insert(request);
        locked.setActivityId(activity.getId()); requests.updateById(locked);
        return activity;
    }

    private CampaignActivity insert(ActivityRequest request) {
        return campaigns.create(request.getName(), request.getWarmupStartTime(), request.getStartTime(),
                request.getEndTime(), request.getActivityType(), request.getBudgetAmount());
    }
}
