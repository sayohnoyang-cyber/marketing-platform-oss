package com.sayohno.marketing.coupon.web;

import com.sayohno.marketing.common.result.Result;
import com.sayohno.marketing.common.result.Results;
import com.sayohno.marketing.coupon.service.VoucherRedemptionService;
import com.sayohno.marketing.coupon.service.VoucherWalletService;
import com.sayohno.marketing.coupon.web.dto.OrderVoucherRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

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

    @GetMapping("/{id}")
    public Result<?> find(@PathVariable long id) {
        var coupon = wallet.requireManaged(id);
        var view = new LinkedHashMap<>(wallet.managedView(coupon));
        view.put("redemptions", settlements.history(coupon.getUserId(), id));
        return Results.success(view);
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
