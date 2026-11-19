package com.sayohno.marketing.coupon.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.common.outbox.OutboxStore;
import com.sayohno.marketing.common.result.ErrorCode;
import com.sayohno.marketing.coupon.dao.entity.VoucherRedemption;
import com.sayohno.marketing.coupon.dao.entity.OfferPlan;
import com.sayohno.marketing.coupon.dao.entity.IssuedVoucher;
import com.sayohno.marketing.coupon.dao.mapper.VoucherRedemptionMapper;
import com.sayohno.marketing.coupon.dao.mapper.OfferPlanMapper;
import com.sayohno.marketing.coupon.dao.mapper.IssuedVoucherMapper;
import com.sayohno.marketing.coupon.enums.RedemptionStatus;
import com.sayohno.marketing.coupon.enums.VoucherStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
public class VoucherRedemptionService {
    public static final String REDEEM_EVENT = "coupon.redeem";
    public static final String REFUND_EVENT = "coupon.refund";
    private final IssuedVoucherMapper userMapper;
    private final OfferPlanMapper templateMapper;
    private final VoucherRedemptionMapper settlementMapper;
    private final OutboxStore outboxStore;

    public VoucherRedemptionService(IssuedVoucherMapper userMapper,
                                   OfferPlanMapper templateMapper,
                                   VoucherRedemptionMapper settlementMapper,
                                   OutboxStore outboxStore) {
        this.userMapper = userMapper;
        this.templateMapper = templateMapper;
        this.settlementMapper = settlementMapper;
        this.outboxStore = outboxStore;
    }

    public BigDecimal quote(long userId, long couponId, BigDecimal orderAmount, String goodsNumber) {
        IssuedVoucher coupon = requireUsable(userId, couponId);
        OfferPlan template = requireTemplate(coupon.getPlanId());
        return DiscountCalculator.discount(template, orderAmount, goodsNumber);
    }

    @Transactional
    public VoucherRedemption lock(long userId, long couponId, String orderNo, BigDecimal orderAmount, String goodsNumber) {
        requireOrder(orderNo);
        validateMoney(orderAmount, false, "订单金额");
        VoucherRedemption existing = find(userId, couponId, orderNo);
        if (existing != null) {
            if (existing.getStatus() == RedemptionStatus.HELD || existing.getStatus() == RedemptionStatus.COMPLETED) {
                BigDecimal discount = DiscountCalculator.discount(
                        requireTemplate(userMapper.selectById(couponId).getPlanId()), orderAmount, goodsNumber);
                if (discount.compareTo(existing.getDiscountAmount()) != 0
                        || (existing.getOrderAmount() != null && orderAmount.compareTo(existing.getOrderAmount()) != 0)) {
                    throw new BizException(ErrorCode.CONFLICT, "同一订单的优惠参数不一致");
                }
                return existing;
            }
            throw new BizException(ErrorCode.CONFLICT, "该订单的优惠券结算已结束");
        }
        IssuedVoucher coupon = requireUsable(userId, couponId);
        if (openSettlement(userId, couponId) != null) {
            throw new BizException(ErrorCode.CONFLICT, "优惠券已锁定或已核销");
        }
        BigDecimal discount = DiscountCalculator.discount(requireTemplate(coupon.getPlanId()), orderAmount, goodsNumber);
        int locked = userMapper.update(null, Wrappers.<IssuedVoucher>lambdaUpdate()
                .eq(IssuedVoucher::getId, couponId)
                .eq(IssuedVoucher::getUserId, userId)
                .eq(IssuedVoucher::getStatus, VoucherStatus.AVAILABLE)
                .set(IssuedVoucher::getStatus, VoucherStatus.HELD));
        if (locked != 1) {
            throw new BizException(ErrorCode.CONFLICT, "优惠券状态不允许锁定");
        }
        VoucherRedemption settlement = new VoucherRedemption();
        settlement.setUserId(userId);
        settlement.setVoucherId(couponId);
        settlement.setOrderNo(orderNo);
        settlement.setDiscountAmount(discount);
        settlement.setOrderAmount(orderAmount);
        settlement.setStatus(RedemptionStatus.HELD);
        try {
            settlementMapper.insert(settlement);
        } catch (DataIntegrityViolationException exception) {
            throw new BizException(ErrorCode.CONFLICT, "订单与优惠券已存在结算记录");
        }
        return settlement;
    }

    @Transactional
    public VoucherRedemption pay(long userId, long couponId, String orderNo) {
        VoucherRedemption settlement = requireSettlement(userId, couponId, orderNo);
        if (settlement.getStatus() == RedemptionStatus.COMPLETED) {
            return settlement;
        }
        if (settlement.getStatus() != RedemptionStatus.HELD) {
            throw new BizException(ErrorCode.CONFLICT, "只有锁定中的结算可以核销");
        }
        int paid = settlementMapper.update(null, Wrappers.<VoucherRedemption>lambdaUpdate()
                .eq(VoucherRedemption::getId, settlement.getId())
                .eq(VoucherRedemption::getStatus, RedemptionStatus.HELD)
                .set(VoucherRedemption::getStatus, RedemptionStatus.COMPLETED));
        int used = userMapper.update(null, Wrappers.<IssuedVoucher>lambdaUpdate()
                .eq(IssuedVoucher::getId, couponId)
                .eq(IssuedVoucher::getUserId, userId)
                .eq(IssuedVoucher::getStatus, VoucherStatus.HELD)
                .set(IssuedVoucher::getStatus, VoucherStatus.REDEEMED));
        if (paid != 1 || used != 1) {
            throw new BizException(ErrorCode.CONFLICT, "核销状态冲突");
        }
        outboxStore.append("redeem:" + settlement.getId(), REDEEM_EVENT, String.valueOf(couponId),
                "{\"settlementId\":" + settlement.getId() + ",\"voucherId\":" + couponId + "}");
        settlement.setStatus(RedemptionStatus.COMPLETED);
        return settlement;
    }

    /**
     * 商家核销台一步直接核销（适用于线下扫码/输券码场景）：在同一事务内完成校验、算价、核销与事件投递。
     * 同订单号且参数一致时幂等返回；若订单已处于锁定态则直接流转为已核销。
     */
    @Transactional
    public VoucherRedemption redeemDirect(long userId, long couponId, String orderNo, BigDecimal orderAmount, String goodsNumber) {
        requireOrder(orderNo);
        validateMoney(orderAmount, false, "订单金额");
        VoucherRedemption existing = find(userId, couponId, orderNo);
        if (existing != null) {
            BigDecimal discount = DiscountCalculator.discount(
                    requireTemplate(userMapper.selectById(couponId).getPlanId()), orderAmount, goodsNumber);
            if (discount.compareTo(existing.getDiscountAmount()) != 0
                    || (existing.getOrderAmount() != null && orderAmount.compareTo(existing.getOrderAmount()) != 0)) {
                throw new BizException(ErrorCode.CONFLICT, "同一订单的优惠参数不一致");
            }
            if (existing.getStatus() == RedemptionStatus.COMPLETED) {
                return existing;
            }
            if (existing.getStatus() == RedemptionStatus.HELD) {
                return pay(userId, couponId, orderNo);
            }
            throw new BizException(ErrorCode.CONFLICT, "该订单的优惠券结算已结束");
        }
        IssuedVoucher coupon = requireUsable(userId, couponId);
        if (openSettlement(userId, couponId) != null) {
            throw new BizException(ErrorCode.CONFLICT, "优惠券已锁定或已核销");
        }
        BigDecimal discount = DiscountCalculator.discount(requireTemplate(coupon.getPlanId()), orderAmount, goodsNumber);
        int used = userMapper.update(null, Wrappers.<IssuedVoucher>lambdaUpdate()
                .eq(IssuedVoucher::getId, couponId)
                .eq(IssuedVoucher::getUserId, userId)
                .eq(IssuedVoucher::getStatus, VoucherStatus.AVAILABLE)
                .set(IssuedVoucher::getStatus, VoucherStatus.REDEEMED));
        if (used != 1) {
            throw new BizException(ErrorCode.CONFLICT, "优惠券状态不允许核销");
        }
        VoucherRedemption settlement = new VoucherRedemption();
        settlement.setUserId(userId);
        settlement.setVoucherId(couponId);
        settlement.setOrderNo(orderNo);
        settlement.setDiscountAmount(discount);
        settlement.setOrderAmount(orderAmount);
        settlement.setStatus(RedemptionStatus.COMPLETED);
        try {
            settlementMapper.insert(settlement);
        } catch (DataIntegrityViolationException exception) {
            throw new BizException(ErrorCode.CONFLICT, "订单与优惠券已存在结算记录");
        }
        outboxStore.append("redeem:" + settlement.getId(), REDEEM_EVENT, String.valueOf(couponId),
                "{\"settlementId\":" + settlement.getId() + ",\"voucherId\":" + couponId + "}");
        return settlement;
    }

    @Transactional
    public VoucherRedemption refund(long userId, long couponId, String orderNo) {
        return refund(userId, couponId, orderNo, null);
    }

    @Transactional
    public VoucherRedemption refund(long userId, long couponId, String orderNo, BigDecimal refundAmount) {
        VoucherRedemption settlement = requireSettlement(userId, couponId, orderNo);
        if (refundAmount != null) {
            validateMoney(refundAmount, true, "退款金额");
            if (settlement.getOrderAmount() == null) {
                throw new BizException(ErrorCode.CONFLICT, "历史结算缺少订单金额，无法校验退款金额");
            }
            BigDecimal paid = settlement.getOrderAmount().subtract(settlement.getDiscountAmount());
            if (refundAmount.compareTo(paid) != 0) {
                throw new BizException(ErrorCode.VALIDATION, "当前仅支持整单退券，退款金额应为实付金额 " + paid.toPlainString());
            }
        }
        if (settlement.getStatus() == RedemptionStatus.REFUNDED) {
            if (refundAmount != null && settlement.getRefundAmount() != null
                    && refundAmount.compareTo(settlement.getRefundAmount()) != 0) {
                throw new BizException(ErrorCode.CONFLICT, "同一结算的退款金额不一致");
            }
            if (refundAmount != null && settlement.getRefundAmount() == null) {
                int updated = settlementMapper.update(null, Wrappers.<VoucherRedemption>lambdaUpdate()
                        .eq(VoucherRedemption::getId, settlement.getId())
                        .eq(VoucherRedemption::getStatus, RedemptionStatus.REFUNDED)
                        .isNull(VoucherRedemption::getRefundAmount).set(VoucherRedemption::getRefundAmount, refundAmount));
                if (updated != 1) throw new BizException(ErrorCode.CONFLICT, "退款记录已更新，请重新读取");
                settlement.setRefundAmount(refundAmount);
            }
            return settlement;
        }
        if (settlement.getStatus() != RedemptionStatus.COMPLETED) {
            throw new BizException(ErrorCode.CONFLICT, "只有已核销的结算可以退款");
        }
        LocalDateTime refundedAt = LocalDateTime.now();
        int refunded = settlementMapper.update(null, Wrappers.<VoucherRedemption>lambdaUpdate()
                .eq(VoucherRedemption::getId, settlement.getId())
                .eq(VoucherRedemption::getStatus, RedemptionStatus.COMPLETED)
                .set(VoucherRedemption::getStatus, RedemptionStatus.REFUNDED)
                .set(VoucherRedemption::getRefundAmount, refundAmount)
                .set(VoucherRedemption::getRefundedAt, refundedAt));
        int restored = userMapper.update(null, Wrappers.<IssuedVoucher>lambdaUpdate()
                .eq(IssuedVoucher::getId, couponId)
                .eq(IssuedVoucher::getUserId, userId)
                .eq(IssuedVoucher::getStatus, VoucherStatus.REDEEMED)
                .set(IssuedVoucher::getStatus, VoucherStatus.AVAILABLE));
        if (refunded != 1 || restored != 1) {
            throw new BizException(ErrorCode.CONFLICT, "退款状态冲突");
        }
        outboxStore.append("refund:" + settlement.getId(), REFUND_EVENT, String.valueOf(couponId),
                "{\"settlementId\":" + settlement.getId() + ",\"voucherId\":" + couponId + "}");
        settlement.setStatus(RedemptionStatus.REFUNDED);
        settlement.setRefundAmount(refundAmount);
        settlement.setRefundedAt(refundedAt);
        return settlement;
    }

    @Transactional
    public VoucherRedemption cancel(long userId, long couponId, String orderNo) {
        VoucherRedemption settlement = requireSettlement(userId, couponId, orderNo);
        if (settlement.getStatus() == RedemptionStatus.VOIDED) {
            return settlement;
        }
        if (settlement.getStatus() != RedemptionStatus.HELD) {
            throw new BizException(ErrorCode.CONFLICT, "只有锁定中的结算可以取消");
        }
        int cancelled = settlementMapper.update(null, Wrappers.<VoucherRedemption>lambdaUpdate()
                .eq(VoucherRedemption::getId, settlement.getId())
                .eq(VoucherRedemption::getStatus, RedemptionStatus.HELD)
                .set(VoucherRedemption::getStatus, RedemptionStatus.VOIDED));
        int restored = userMapper.update(null, Wrappers.<IssuedVoucher>lambdaUpdate()
                .eq(IssuedVoucher::getId, couponId)
                .eq(IssuedVoucher::getUserId, userId)
                .eq(IssuedVoucher::getStatus, VoucherStatus.HELD)
                .set(IssuedVoucher::getStatus, VoucherStatus.AVAILABLE));
        if (cancelled != 1 || restored != 1) {
            throw new BizException(ErrorCode.CONFLICT, "取消锁定状态冲突");
        }
        settlement.setStatus(RedemptionStatus.VOIDED);
        return settlement;
    }

    public java.util.List<VoucherRedemption> history(long userId, long couponId) {
        IssuedVoucher coupon = userMapper.selectById(couponId);
        if (coupon == null || !Long.valueOf(userId).equals(coupon.getUserId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "优惠券不存在");
        }
        return settlementMapper.selectList(Wrappers.<VoucherRedemption>lambdaQuery()
                .eq(VoucherRedemption::getUserId, userId).eq(VoucherRedemption::getVoucherId, couponId)
                .orderByDesc(VoucherRedemption::getId).last("LIMIT 50"));
    }

    private void validateMoney(BigDecimal amount, boolean allowZero, String label) {
        if (amount == null || amount.signum() < 0 || (!allowZero && amount.signum() == 0)
                || amount.scale() > 2 || amount.compareTo(new BigDecimal("9999999999.99")) > 0) {
            throw new BizException(ErrorCode.VALIDATION, "请填写有效的" + label + "，最多两位小数");
        }
    }

    private IssuedVoucher requireUsable(long userId, long couponId) {
        IssuedVoucher coupon = userMapper.selectOne(Wrappers.<IssuedVoucher>lambdaQuery()
                .eq(IssuedVoucher::getId, couponId)
                .eq(IssuedVoucher::getUserId, userId));
        if (coupon == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "优惠券不存在");
        }
        if (coupon.getUsableUntil().isBefore(LocalDateTime.now())) {
            throw new BizException(ErrorCode.VALIDATION, "优惠券已过期");
        }
        if (coupon.getStatus() != VoucherStatus.AVAILABLE) {
            throw new BizException(ErrorCode.CONFLICT, "优惠券状态不允许使用");
        }
        return coupon;
    }

    private OfferPlan requireTemplate(long templateId) {
        OfferPlan template = templateMapper.selectById(templateId);
        if (template == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "优惠券模板不存在");
        }
        return template;
    }

    private VoucherRedemption find(long userId, long couponId, String orderNo) {
        return settlementMapper.selectOne(Wrappers.<VoucherRedemption>lambdaQuery()
                .eq(VoucherRedemption::getUserId, userId)
                .eq(VoucherRedemption::getVoucherId, couponId)
                .eq(VoucherRedemption::getOrderNo, orderNo));
    }

    private VoucherRedemption openSettlement(long userId, long couponId) {
        return settlementMapper.selectOne(Wrappers.<VoucherRedemption>lambdaQuery()
                .eq(VoucherRedemption::getUserId, userId)
                .eq(VoucherRedemption::getVoucherId, couponId)
                .in(VoucherRedemption::getStatus, RedemptionStatus.HELD, RedemptionStatus.COMPLETED)
                .last("limit 1"));
    }

    private VoucherRedemption requireSettlement(long userId, long couponId, String orderNo) {
        requireOrder(orderNo);
        VoucherRedemption settlement = find(userId, couponId, orderNo);
        if (settlement == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "结算记录不存在");
        }
        return settlement;
    }

    private void requireOrder(String orderNo) {
        if (orderNo == null || orderNo.isBlank() || orderNo.length() > 64) {
            throw new BizException(ErrorCode.VALIDATION, "订单号不合法");
        }
    }
}
