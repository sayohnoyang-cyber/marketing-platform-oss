package com.sayohno.marketing.coupon.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sayohno.marketing.common.context.UserContext;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.common.result.ErrorCode;
import com.sayohno.marketing.coupon.dao.entity.OfferPlan;
import com.sayohno.marketing.coupon.dao.entity.IssuedVoucher;
import com.sayohno.marketing.coupon.dao.mapper.OfferPlanMapper;
import com.sayohno.marketing.coupon.dao.mapper.IssuedVoucherMapper;
import com.sayohno.marketing.coupon.enums.VoucherStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class VoucherWalletService {
    private final IssuedVoucherMapper users;
    private final OfferPlanMapper templates;

    public VoucherWalletService(IssuedVoucherMapper users, OfferPlanMapper templates) {
        this.users = users;
        this.templates = templates;
    }

    public Page<Map<String, Object>> mine(long userId, long page, long size) {
        var result = users.selectPage(new Page<IssuedVoucher>(Math.max(1, page), Math.min(100, Math.max(1, size))),
                Wrappers.<IssuedVoucher>lambdaQuery().eq(IssuedVoucher::getUserId, userId).orderByDesc(IssuedVoucher::getId));
        // 一页只批量读取一次模板，避免逐券查询；返回券面所需字段，不暴露工作空间配置。
        var ids = result.getRecords().stream().map(IssuedVoucher::getPlanId).distinct().toList();
        Map<Long, OfferPlan> definitions = ids.isEmpty() ? Map.of() : templates.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(OfferPlan::getId, Function.identity()));
        var view = new Page<Map<String, Object>>(result.getCurrent(), result.getSize(), result.getTotal());
        view.setRecords(result.getRecords().stream().map(coupon -> present(coupon, definitions.get(coupon.getPlanId()))).toList());
        return view;
    }

    public IssuedVoucher requireManaged(long couponId) {
        var current = UserContext.require();
        if (!current.hasMerchantAccess()) throw new BizException(ErrorCode.FORBIDDEN, "需要工作空间管理权限");
        IssuedVoucher coupon = users.selectById(couponId);
        OfferPlan template = coupon == null ? null : templates.selectById(coupon.getPlanId());
        // 券所属模板必须属于当前工作空间，领取用户和商家身份均不接受客户端指定。
        if (template == null || !current.getMerchantId().equals(template.getMerchantId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "优惠券不存在");
        }
        return coupon;
    }

    public Map<String, Object> managedView(IssuedVoucher coupon) {
        return present(coupon, templates.selectById(coupon.getPlanId()));
    }

    private Map<String, Object> present(IssuedVoucher coupon, OfferPlan template) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", coupon.getId());
        view.put("title", template == null ? "优惠券" : template.getTitle());
        view.put("benefitPolicy", template == null ? null : template.getBenefitPolicy());
        view.put("usableFrom", coupon.getUsableFrom());
        view.put("usableUntil", coupon.getUsableUntil());
        view.put("grantedAt", coupon.getGrantedAt());
        VoucherStatus state = coupon.getStatus();
        if (state == VoucherStatus.AVAILABLE && coupon.getUsableUntil() != null
                && !LocalDateTime.now().isBefore(coupon.getUsableUntil())) state = VoucherStatus.EXPIRED;
        view.put("status", state);
        view.put("usable", state == VoucherStatus.AVAILABLE && coupon.getUsableFrom() != null
                && !LocalDateTime.now().isBefore(coupon.getUsableFrom()));
        return view;
    }
}
