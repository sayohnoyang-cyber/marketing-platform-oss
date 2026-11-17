package com.sayohno.marketing.campaign.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sayohno.marketing.campaign.dao.entity.CampaignActivity;
import com.sayohno.marketing.campaign.dao.entity.CampaignOffer;
import com.sayohno.marketing.campaign.dao.mapper.CampaignOfferMapper;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.coupon.dao.entity.OfferInventory;
import com.sayohno.marketing.coupon.dao.entity.OfferPlan;
import com.sayohno.marketing.coupon.enums.InventoryStatus;
import com.sayohno.marketing.coupon.enums.OfferPlanStatus;
import com.sayohno.marketing.coupon.service.OfferInventoryService;
import com.sayohno.marketing.coupon.service.OfferPolicyRules;
import com.sayohno.marketing.coupon.service.OfferPlanService;
import com.sayohno.marketing.shortlink.dao.entity.Link;
import com.sayohno.marketing.shortlink.service.LinkDirectoryService;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CampaignPublishService {
    private final CampaignOfferMapper couponMapper;
    private final OfferPlanService templateService;
    private final OfferInventoryService allocationService;
    private final LinkDirectoryService linkDirectoryService;

    public CampaignPublishService(CampaignOfferMapper couponMapper,
                                  OfferPlanService templateService,
                                  OfferInventoryService allocationService,
                                  LinkDirectoryService linkDirectoryService) {
        this.couponMapper = couponMapper;
        this.templateService = templateService;
        this.allocationService = allocationService;
        this.linkDirectoryService = linkDirectoryService;
    }

    public List<Map<String, Object>> checks(CampaignActivity activity) {
        List<CampaignOffer> coupons = couponMapper.selectList(Wrappers.<CampaignOffer>lambdaQuery()
                .eq(CampaignOffer::getActivityId, activity.getId()));
        OfferPlan template = null;
        String templateLoadError = null;
        if (coupons.size() == 1) {
            try {
                template = templateService.requireOwned(coupons.get(0).getPlanId());
            } catch (BizException exception) {
                templateLoadError = exception.getMessage();
            }
        }
        OfferInventory allocation = null;
        String allocationLoadError = null;
        if (template != null && coupons.get(0).getInventoryId() != null) {
            try {
                allocation = allocationService.requireOwned(coupons.get(0).getInventoryId());
            } catch (BizException exception) {
                allocationLoadError = exception.getMessage();
            }
        }
        Link link = null;
        String linkLoadError = null;
        if (activity.getMainLinkId() != null && activity.getMainEnabled() != null && activity.getMainEnabled() == 1) {
            try {
                link = linkDirectoryService.findOwned(activity.getMainLinkId());
            } catch (BizException exception) {
                linkLoadError = exception.getMessage();
            }
        }
        return checksFromLoaded(activity, coupons, template, templateLoadError, allocation, allocationLoadError, link, linkLoadError);
    }

    /**
     * 基于已批量加载的关联对象执行发布阻断项检查，供活动列表页消除 N+1 查询。
     */
    public List<Map<String, Object>> checks(CampaignActivity activity, List<CampaignOffer> coupons,
                                            OfferPlan template, OfferInventory allocation, Link link) {
        String templateError = coupons != null && coupons.size() == 1 && template == null ? "优惠券模板不存在" : null;
        String allocationError = template != null && coupons.get(0).getInventoryId() != null && allocation == null ? "投放不存在" : null;
        String linkError = activity.getMainLinkId() != null && Integer.valueOf(1).equals(activity.getMainEnabled()) && link == null ? "主链不存在" : null;
        return checksFromLoaded(activity, coupons == null ? List.of() : coupons, template, templateError, allocation, allocationError, link, linkError);
    }

    private List<Map<String, Object>> checksFromLoaded(CampaignActivity activity, List<CampaignOffer> coupons,
                                                       OfferPlan loadedTemplate, String templateLoadError,
                                                       OfferInventory loadedAllocation, String allocationLoadError,
                                                       Link loadedLink, String linkLoadError) {
        List<Map<String, Object>> items = new ArrayList<>();
        boolean time = activity.getStartTime().isAfter(activity.getWarmupStartTime())
                && activity.getEndTime().isAfter(activity.getStartTime());
        items.add(item("time", time, time ? "" : "预热、开场和结束时间必须依次排开", "info"));
        OfferPlan template = null;
        String templateReason = "";
        if (coupons.size() != 1) {
            templateReason = "第一版每场活动只选一张券";
        } else if (loadedTemplate == null) {
            templateReason = templateLoadError != null ? templateLoadError : "优惠券模板不存在";
        } else {
            template = loadedTemplate;
            if (template.getStatus() != OfferPlanStatus.OPEN) {
                templateReason = "券模板不是生效状态";
                template = null;
            } else if (template.getUsableFrom().isAfter(activity.getStartTime())) {
                templateReason = "券生效时间晚于活动开场时间";
                template = null;
            } else if (template.getUsableUntil().isBefore(activity.getEndTime())) {
                templateReason = "券有效期没有覆盖活动结束时间";
                template = null;
            }
        }
        items.add(item("template", template != null, templateReason, "pick"));
        OfferInventory allocation = null;
        String allocationReason = "";
        if (template == null || coupons.get(0).getInventoryId() == null) {
            allocationReason = "还没有设置本场投放数量";
        } else if (loadedAllocation == null) {
            allocationReason = allocationLoadError != null ? allocationLoadError : "投放不存在";
        } else {
            allocation = loadedAllocation;
            if (allocation.getStatus() != InventoryStatus.OPEN || allocation.getQuantity() < 1) {
                allocationReason = "投放数量不合法或已冻结";
                allocation = null;
            } else if (allocation.getMaxClaimsPerUser() > OfferPolicyRules.maxClaimsPerUser(template.getClaimPolicy())) {
                allocationReason = "本场限领高于模板总限领";
                allocation = null;
            }
        }
        items.add(item("allocation", allocation != null, allocationReason, "pick"));
        boolean main = false;
        String mainReason = "主链还没有启用";
        if (activity.getMainLinkId() != null && activity.getMainEnabled() != null && activity.getMainEnabled() == 1) {
            if (loadedLink == null) {
                mainReason = linkLoadError != null ? linkLoadError : "主链短链不存在";
            } else {
                main = loadedLink.getEnableStatus() != null && loadedLink.getEnableStatus() == 0;
                if (!main) {
                    mainReason = "主链短链已停用";
                }
            }
        }
        items.add(item("main-link", main, main ? "" : mainReason, "link"));
        boolean budget = false;
        String budgetReason = "请设置预算上限";
        if (allocation != null && template != null && activity.getBudgetAmount() != null) {
            try {
                BigDecimal max = OfferPolicyRules.maximumDiscount(template.getBenefitPolicy())
                        .multiply(BigDecimal.valueOf(allocation.getQuantity()));
                budget = activity.getBudgetAmount().compareTo(max) >= 0;
                budgetReason = budget ? "" : "最大让利 " + max + " 超过预算 " + activity.getBudgetAmount();
            } catch (BizException exception) {
                budgetReason = exception.getMessage();
            }
        }
        items.add(item("budget", budget, budgetReason, "pick"));
        return items;
    }

    private static Map<String, Object> item(String code, boolean passed, String reason, String fix) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("code", code);
        row.put("passed", passed);
        row.put("blocking", true);
        row.put("level", passed ? "ok" : "block");
        row.put("reason", passed ? "" : reason);
        row.put("fix", passed ? "" : fix);
        return row;
    }
}
