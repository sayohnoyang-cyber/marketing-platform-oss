package com.sayohno.marketing.coupon.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sayohno.marketing.common.campaign.DirectClaimGuard;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.common.result.ErrorCode;
import com.sayohno.marketing.coupon.dao.entity.OfferPlan;
import com.sayohno.marketing.coupon.dao.entity.OfferPlanAudit;
import com.sayohno.marketing.coupon.dao.mapper.OfferPlanAuditMapper;
import com.sayohno.marketing.coupon.dao.mapper.OfferPlanMapper;
import com.sayohno.marketing.coupon.enums.ApplyScope;
import com.sayohno.marketing.coupon.enums.BenefitKind;
import com.sayohno.marketing.coupon.enums.IssuerScope;
import com.sayohno.marketing.coupon.enums.OfferPlanStatus;
import com.sayohno.marketing.coupon.stock.OfferStockGateway;
import com.sayohno.marketing.identity.service.MerchantAccessService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class OfferPlanService {
    private final OfferPlanMapper templateMapper;
    private final OfferPlanAuditMapper logMapper;
    private final MerchantAccessService merchantAccessService;
    private final OfferStockGateway stockGateway;
    private final org.springframework.beans.factory.ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> redis;
    private final com.fasterxml.jackson.databind.ObjectMapper json;
    private final org.springframework.beans.factory.ObjectProvider<DirectClaimGuard> campaignGuards;

    public OfferPlanService(OfferPlanMapper templateMapper,
                                 OfferPlanAuditMapper logMapper,
                                 MerchantAccessService merchantAccessService,
                                 OfferStockGateway stockGateway,
                                 org.springframework.beans.factory.ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> redis,
                                 com.fasterxml.jackson.databind.ObjectMapper json) {
        this(templateMapper, logMapper, merchantAccessService, stockGateway, redis, json, null);
    }

    @Autowired
    public OfferPlanService(OfferPlanMapper templateMapper,
                                 OfferPlanAuditMapper logMapper,
                                 MerchantAccessService merchantAccessService,
                                 OfferStockGateway stockGateway,
                                 org.springframework.beans.factory.ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> redis,
                                 com.fasterxml.jackson.databind.ObjectMapper json,
                                 org.springframework.beans.factory.ObjectProvider<DirectClaimGuard> campaignGuards) {
        this.templateMapper = templateMapper;
        this.logMapper = logMapper;
        this.merchantAccessService = merchantAccessService;
        this.stockGateway = stockGateway;
        this.redis = redis;
        this.json = json;
        this.campaignGuards = campaignGuards;
    }

    @Transactional
    public OfferPlan create(String storeCode, String name, IssuerScope source, ApplyScope target, String goods, BenefitKind type,
                                 int stock, LocalDateTime validStart, LocalDateTime validEnd,
                                 String claimPolicy, String benefitPolicy) {
        var merchant = merchantAccessService.requireMerchant();
        if (storeCode != null && !storeCode.isBlank()) merchantAccessService.requireShop(storeCode);
        validate(name, source, target, goods, type, stock, validStart, validEnd, claimPolicy, benefitPolicy);
        OfferPlan template = new OfferPlan();
        template.setMerchantId(merchant.getMerchantId());
        template.setStoreCode(storeCode == null || storeCode.isBlank() ? null : storeCode);
        template.setTitle(name.trim());
        template.setIssuerScope(source);
        template.setApplyScope(target);
        template.setSkuCode(goods);
        template.setBenefitKind(type);
        template.setInventoryQty(stock);
        template.setIssuedTotal(stock);
        template.setUsableFrom(validStart);
        template.setUsableUntil(validEnd);
        template.setClaimPolicy(claimPolicy);
        template.setBenefitPolicy(benefitPolicy);
        template.setStatus(OfferPlanStatus.OPEN);
        templateMapper.insert(template);
        log(template.getId(), merchant.getUserId(), "CREATE", "stock=" + stock);
        return template;
    }

    public Page<OfferPlan> page(long page, long size) {
        var merchant = merchantAccessService.requireMerchant();
        long safeSize = Math.min(Math.max(size, 1), 100);
        return templateMapper.selectPage(new Page<>(Math.max(page, 1), safeSize),
                Wrappers.<OfferPlan>lambdaQuery()
                        .eq(OfferPlan::getMerchantId, merchant.getMerchantId())
                        .orderByDesc(OfferPlan::getId));
    }

    public OfferPlan requireOwned(long templateId) {
        var merchant = merchantAccessService.requireMerchant();
        OfferPlan template = templateMapper.selectById(templateId);
        if (template == null || !merchant.getMerchantId().equals(template.getMerchantId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "优惠券模板不存在");
        }
        return template;
    }

    public java.util.Map<Long, OfferPlan> findOwnedBatch(java.util.Collection<Long> templateIds) {
        if (templateIds == null || templateIds.isEmpty()) {
            return java.util.Map.of();
        }
        var merchant = merchantAccessService.requireMerchant();
        return templateMapper.selectBatchIds(templateIds).stream()
                .filter(t -> merchant.getMerchantId().equals(t.getMerchantId()))
                .collect(java.util.stream.Collectors.toMap(OfferPlan::getId, t -> t, (a, b) -> a));
    }

    /** Public offer fields only. The caller must resolve the owning campaign first. */
    public java.util.Map<String, Object> publicOffer(long templateId, long merchantId) {
        OfferPlan template = templateMapper.selectById(templateId);
        if (template == null || !Long.valueOf(merchantId).equals(template.getMerchantId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "优惠券模板不存在");
        }
        java.util.Map<String, Object> offer = new java.util.LinkedHashMap<>();
        offer.put("couponName", template.getTitle());
        offer.put("couponRule", template.getBenefitPolicy());
        offer.put("couponValidEndTime", template.getUsableUntil());
        offer.put("couponStatus", template.getStatus());
        var cache = redis.getIfAvailable();
        if (cache != null) {
            try {
                String encoded = json.writeValueAsString(offer);
                String key = "coupon:offer:" + templateId + ":" + com.sayohno.marketing.common.hash.RequestHashes.sha256(encoded);
                String value = cache.opsForValue().get(key);
                if (encoded.equals(value)) return offer;
                cache.opsForValue().set(key, encoded, java.time.Duration.ofMinutes(5));
            } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ignored) {
                // Offer reads remain available when the optional read cache is unavailable.
            }
        }
        return offer;
    }

    public java.util.Map<String, Object> warmOffer(long templateId) {
        OfferPlan template = requireOwned(templateId);
        var cache = redis.getIfAvailable();
        if (cache == null) return java.util.Map.of("status", "UNVERIFIED", "detail", "Redis未启用，无法读回券规则缓存");
        var offer = publicOffer(templateId, template.getMerchantId());
        try {
            String encoded = json.writeValueAsString(offer);
            String key = "coupon:offer:" + templateId + ":" + com.sayohno.marketing.common.hash.RequestHashes.sha256(encoded);
            boolean matched = encoded.equals(cache.opsForValue().get(key));
            return java.util.Map.of("status", matched ? "SUCCEEDED" : "FAILED", "detail", matched ? "券展示规则缓存写入并读回一致" : "券规则缓存读回不一致");
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }

    @Transactional
    public OfferPlan increaseStock(long templateId, int count) {
        var merchant = merchantAccessService.requireMerchant();
        if (count < 1) {
            throw new BizException(ErrorCode.VALIDATION, "增加库存必须大于 0");
        }
        OfferPlan template = requireOwned(templateId);
        if (templateMapper.increaseStock(templateId, merchant.getMerchantId(), count, LocalDateTime.now()) != 1) {
            throw new BizException(ErrorCode.NOT_FOUND, "优惠券模板不存在");
        }
        stockGateway.increaseIfPresent(templateId, count);
        log(templateId, merchant.getUserId(), "INCREASE_STOCK", "count=" + count);
        return templateMapper.selectById(templateId);
    }

    @Transactional
    public OfferPlan revise(long templateId, String name, LocalDateTime validStart, LocalDateTime validEnd,
                                 String claimPolicy, String benefitPolicy) {
        var merchant = merchantAccessService.requireMerchant();
        OfferPlan template = requireOwned(templateId);
        if (name != null && !name.isBlank()) {
            template.setTitle(name.trim());
        }
        if (validStart != null) {
            template.setUsableFrom(validStart);
        }
        if (validEnd != null) {
            template.setUsableUntil(validEnd);
        }
        if (claimPolicy != null && !claimPolicy.isBlank()) {
            OfferPolicyRules.maxClaimsPerUser(claimPolicy);
            template.setClaimPolicy(claimPolicy);
        }
        if (benefitPolicy != null && !benefitPolicy.isBlank()) {
            OfferPolicyRules.parse(benefitPolicy, "核销规则");
            template.setBenefitPolicy(benefitPolicy);
        }
        if (!template.getUsableUntil().isAfter(template.getUsableFrom())) {
            throw new BizException(ErrorCode.VALIDATION, "领取时间不合法");
        }
        int maxClaimsPerUser = OfferPolicyRules.maxClaimsPerUser(template.getClaimPolicy());
        if (campaignGuards != null) {
            campaignGuards.forEach(guard -> guard.assertTemplateRevisionAllowed(
                    templateId, template.getUsableFrom(), template.getUsableUntil(), maxClaimsPerUser));
        }
        templateMapper.updateById(template);
        log(templateId, merchant.getUserId(), "REVISE", null);
        return template;
    }

    @Transactional
    public OfferPlan terminate(long templateId) {
        var merchant = merchantAccessService.requireMerchant();
        OfferPlan template = requireOwned(templateId);
        if (campaignGuards != null) {
            campaignGuards.forEach(guard -> guard.assertTemplateTerminationAllowed(templateId));
        }
        template.setStatus(OfferPlanStatus.CLOSED);
        templateMapper.updateById(template);
        log(templateId, merchant.getUserId(), "TERMINATE", null);
        return template;
    }

    private void validate(String name, IssuerScope source, ApplyScope target, String goods, BenefitKind type, int stock,
                          LocalDateTime validStart, LocalDateTime validEnd, String claimPolicy, String benefitPolicy) {
        if (name == null || name.isBlank() || name.length() > 256) {
            throw new BizException(ErrorCode.VALIDATION, "模板名称不合法");
        }
        if (source == null) {
            throw new BizException(ErrorCode.VALIDATION, "来源不能为空");
        }
        if (type == null) {
            throw new BizException(ErrorCode.VALIDATION, "无效的优惠券类型");
        }
        if (target == ApplyScope.SKU && (goods == null || goods.isBlank())) {
            throw new BizException(ErrorCode.VALIDATION, "商品券必须填写商品编号");
        }
        if (stock < 0) {
            throw new BizException(ErrorCode.VALIDATION, "库存不能为负数");
        }
        if (validStart == null || validEnd == null || !validEnd.isAfter(validStart)) {
            throw new BizException(ErrorCode.VALIDATION, "领取时间不合法");
        }
        OfferPolicyRules.maxClaimsPerUser(claimPolicy);
        OfferPolicyRules.parse(benefitPolicy, "核销规则");
    }

    private void log(long templateId, long operatorId, String operation, String detail) {
        OfferPlanAudit row = new OfferPlanAudit();
        row.setPlanId(templateId);
        row.setOperatorId(operatorId);
        row.setAction(operation);
        row.setPayload(detail);
        logMapper.insert(row);
    }
}
