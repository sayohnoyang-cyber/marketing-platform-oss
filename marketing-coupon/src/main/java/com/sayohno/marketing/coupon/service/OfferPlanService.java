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

    /** 仅返回券公开展示字段，并按确定性键 coupon:offer:{templateId} 优先命中 Redis 缓存。 */
    public java.util.Map<String, Object> publicOffer(long templateId, long merchantId) {
        String key = offerCacheKey(templateId);
        var cache = redis.getIfAvailable();
        if (cache != null) {
            try {
                String raw = cache.opsForValue().get(key);
                if (raw != null && !raw.isBlank()) {
                    var node = json.readTree(raw);
                    if (!Long.valueOf(merchantId).equals(node.path("merchantId").asLong(-1L))) {
                        throw new BizException(ErrorCode.NOT_FOUND, "优惠券模板不存在");
                    }
                    java.util.Map<String, Object> cached = new java.util.LinkedHashMap<>();
                    cached.put("couponName", node.path("couponName").asText(""));
                    cached.put("couponRule", node.path("couponRule").asText(""));
                    cached.put("couponValidEndTime", node.hasNonNull("couponValidEndTime")
                            ? LocalDateTime.parse(node.get("couponValidEndTime").asText()) : null);
                    cached.put("couponStatus", node.hasNonNull("couponStatus")
                            ? OfferPlanStatus.fromCode(node.get("couponStatus").asText()) : null);
                    return cached;
                }
            } catch (BizException exception) {
                throw exception;
            } catch (Exception ignored) {
                // 缓存反序列化异常时回源数据库，保障读链路可用。
            }
        }
        OfferPlan template = templateMapper.selectById(templateId);
        if (template == null || !Long.valueOf(merchantId).equals(template.getMerchantId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "优惠券模板不存在");
        }
        java.util.Map<String, Object> offer = new java.util.LinkedHashMap<>();
        offer.put("couponName", template.getTitle());
        offer.put("couponRule", template.getBenefitPolicy());
        offer.put("couponValidEndTime", template.getUsableUntil());
        offer.put("couponStatus", template.getStatus());
        if (cache != null) {
            try {
                java.util.Map<String, Object> cachePayload = new java.util.LinkedHashMap<>();
                cachePayload.put("merchantId", template.getMerchantId());
                cachePayload.put("couponName", template.getTitle());
                cachePayload.put("couponRule", template.getBenefitPolicy());
                cachePayload.put("couponValidEndTime", template.getUsableUntil() == null ? null : template.getUsableUntil().toString());
                cachePayload.put("couponStatus", template.getStatus() == null ? null : template.getStatus().getCode());
                cache.opsForValue().set(key, json.writeValueAsString(cachePayload), java.time.Duration.ofMinutes(5));
            } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ignored) {
                // 写缓存失败不影响返回数据库读取结果。
            }
        }
        return offer;
    }

    /** 预热券展示规则缓存：先清理旧缓存再回源写入并读回校验。 */
    public java.util.Map<String, Object> warmOffer(long templateId) {
        OfferPlan template = requireOwned(templateId);
        var cache = redis.getIfAvailable();
        if (cache == null) return java.util.Map.of("status", "UNVERIFIED", "detail", "Redis未启用，无法读回券规则缓存");
        String key = offerCacheKey(templateId);
        cache.delete(key);
        publicOffer(templateId, template.getMerchantId());
        String readBack = cache.opsForValue().get(key);
        boolean matched = readBack != null && !readBack.isBlank();
        return java.util.Map.of("status", matched ? "SUCCEEDED" : "FAILED",
                "detail", matched ? "券展示规则缓存写入并读回一致" : "券规则缓存读回不一致");
    }

    private static String offerCacheKey(long templateId) {
        return "coupon:offer:" + templateId;
    }

    private void evictOfferCache(long templateId) {
        var cache = redis.getIfAvailable();
        if (cache == null) {
            return;
        }
        Runnable deleteTask = () -> {
            try {
                cache.delete(offerCacheKey(templateId));
            } catch (RuntimeException ignored) {
            }
        };
        deleteTask.run();
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            deleteTask.run();
                        }
                    });
        }
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
        evictOfferCache(templateId);
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
        evictOfferCache(templateId);
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
