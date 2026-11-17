package com.sayohno.marketing.coupon.web;

import com.sayohno.marketing.common.campaign.DirectClaimGuard;
import com.sayohno.marketing.common.context.UserContext;
import com.sayohno.marketing.common.result.Result;
import com.sayohno.marketing.common.result.Results;
import com.sayohno.marketing.coupon.enums.IssuerScope;
import com.sayohno.marketing.coupon.service.VoucherClaimService;
import com.sayohno.marketing.coupon.service.VoucherRedemptionService;
import com.sayohno.marketing.coupon.service.VoucherWalletService;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.common.result.ErrorCode;
import com.sayohno.marketing.coupon.web.dto.ClaimVoucherRequest;
import com.sayohno.marketing.coupon.web.dto.OrderVoucherRequest;
import com.sayohno.marketing.coupon.web.dto.QuoteVoucherRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user/vouchers")
public class VoucherWalletController {
    private final VoucherClaimService claimService;
    private final VoucherRedemptionService settlementService;
    private final VoucherWalletService walletService;
    private final ObjectProvider<DirectClaimGuard> directClaimGuards;

    public VoucherWalletController(VoucherClaimService claimService,
                                VoucherRedemptionService settlementService,
                                VoucherWalletService walletService,
                                ObjectProvider<DirectClaimGuard> directClaimGuards) {
        this.claimService = claimService;
        this.settlementService = settlementService;
        this.walletService = walletService;
        this.directClaimGuards = directClaimGuards;
    }

    @PostMapping("/claims")
    public Result<?> claim(@Valid @RequestBody ClaimVoucherRequest request) {
        long userId = UserContext.require().getUserId();
        directClaimGuards.forEach(guard -> guard.assertDirectClaimAllowed(request.getPlanId()));
        return Results.success(claimService.claim(userId, request.getPlanId(), IssuerScope.MERCHANT, request.getRequestId()));
    }

    @GetMapping
    public Result<?> mine(@RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") long size) {
        return Results.success(walletService.mine(UserContext.require().getUserId(), page, size));
    }

    @PostMapping("/quotes")
    public Result<?> quote(@Valid @RequestBody QuoteVoucherRequest request) {
        return Results.success(settlementService.quote(UserContext.require().getUserId(),
                request.getVoucherId(), request.getOrderAmount(), request.getGoodsNumber()));
    }

    @GetMapping("/{id}/redemptions")
    public Result<?> settlements(@PathVariable long id) {
        return Results.success(settlementService.history(UserContext.require().getUserId(), id));
    }

    @PostMapping("/{id}/hold")
    public Result<?> lock(@PathVariable long id, @Valid @RequestBody OrderVoucherRequest request) {
        return Results.success(settlementService.lock(UserContext.require().getUserId(), id,
                request.getOrderNo(), request.getOrderAmount(), request.getGoodsNumber()));
    }

    @PostMapping("/{id}/complete")
    public Result<?> pay(@PathVariable long id, @Valid @RequestBody OrderVoucherRequest request) {
        throw managementOnly();
    }

    @PostMapping("/{id}/refund")
    public Result<?> refund(@PathVariable long id, @Valid @RequestBody OrderVoucherRequest request) {
        throw managementOnly();
    }

    // 旧用户端接口显式拒绝，避免仅移除前端按钮后仍可自行核销、退款。
    private BizException managementOnly() {
        return new BizException(
                ErrorCode.FORBIDDEN, "请通过工作空间管理入口核销或记录退款");
    }

    @PostMapping("/{id}/void")
    public Result<?> cancel(@PathVariable long id, @Valid @RequestBody OrderVoucherRequest request) {
        return Results.success(settlementService.cancel(UserContext.require().getUserId(), id, request.getOrderNo()));
    }
}
