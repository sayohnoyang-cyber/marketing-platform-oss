package com.sayohno.marketing.coupon.web;

import com.sayohno.marketing.common.result.Result;
import com.sayohno.marketing.common.result.Results;
import com.sayohno.marketing.coupon.service.VoucherRedemptionService;
import com.sayohno.marketing.coupon.service.VoucherWalletService;
import com.sayohno.marketing.coupon.web.dto.OrderVoucherRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

import com.sayohno.marketing.common.context.UserContext;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.common.result.ErrorCode;

import java.util.LinkedHashMap;

@RestController
@RequestMapping("/api/merchant/vouchers")
public class VoucherManagementController {
    private final VoucherWalletService wallet;
    private final VoucherRedemptionService settlements;

    public VoucherManagementController(VoucherWalletService wallet, VoucherRedemptionService settlements) {
        this.wallet = wallet;
        this.settlements = settlements;
    }

    @GetMapping("/redemptions")
    public Result<?> redemptions(@RequestParam(required = false) String keyword,
                                 @RequestParam(required = false) String status,
                                 @RequestParam(defaultValue = "1") long page,
                                 @RequestParam(defaultValue = "20") long size) {
        var current = UserContext.require();
        if (!current.hasMerchantAccess()) {
            throw new BizException(ErrorCode.FORBIDDEN, "需要工作空间管理权限");
        }
        return Results.success(settlements.merchantHistory(current.getMerchantId(), keyword, status, page, size));
    }

    @GetMapping("/{id}")
    public Result<?> find(@PathVariable long id) {
        var coupon = wallet.requireManaged(id);
        var view = new LinkedHashMap<>(wallet.managedView(coupon));
        view.put("redemptions", settlements.history(coupon.getUserId(), id));
        return Results.success(view);
    }

    @PostMapping("/{id}/quote")
    public Result<?> quote(@PathVariable long id, @Valid @RequestBody OrderVoucherRequest request) {
        var coupon = wallet.requireManaged(id);
        return Results.success(settlements.quote(coupon.getUserId(), id, request.getOrderAmount(), request.getGoodsNumber()));
    }

    @PostMapping("/{id}/redeem")
    public Result<?> redeemDirect(@PathVariable long id, @Valid @RequestBody OrderVoucherRequest request) {
        var coupon = wallet.requireManaged(id);
        return Results.success(settlements.redeemDirect(
                coupon.getUserId(), id, request.getOrderNo(), request.getOrderAmount(), request.getGoodsNumber()));
    }

    @PostMapping("/{id}/complete")
    public Result<?> pay(@PathVariable long id, @Valid @RequestBody OrderVoucherRequest request) {
        var coupon = wallet.requireManaged(id);
        return Results.success(settlements.pay(coupon.getUserId(), id, request.getOrderNo()));
    }

    @PostMapping("/{id}/refund")
    public Result<?> refund(@PathVariable long id, @Valid @RequestBody OrderVoucherRequest request) {
        var coupon = wallet.requireManaged(id);
        if (request.getRefundAmount() == null) {
            throw new com.sayohno.marketing.common.exception.BizException(
                    com.sayohno.marketing.common.result.ErrorCode.VALIDATION, "请填写实际退款金额");
        }
        return Results.success(settlements.refund(coupon.getUserId(), id, request.getOrderNo(), request.getRefundAmount()));
    }
}
