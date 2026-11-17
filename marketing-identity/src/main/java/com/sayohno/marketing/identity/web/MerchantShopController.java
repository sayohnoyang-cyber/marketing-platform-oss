package com.sayohno.marketing.identity.web;

import com.sayohno.marketing.common.result.Result;
import com.sayohno.marketing.common.result.Results;
import com.sayohno.marketing.identity.service.AuthService;
import com.sayohno.marketing.identity.web.dto.CreateShopRequest;
import com.sayohno.marketing.identity.web.dto.ShopView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/merchant/shops")
public class MerchantShopController {
    private final AuthService authService;

    public MerchantShopController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping
    public Result<List<ShopView>> list(@RequestParam(value = "merchantId", required = false) Long ignoredMerchantId,
                                       @RequestHeader(value = "merchantId", required = false) String ignoredHeader,
                                       @RequestHeader(value = "gid", required = false) String ignoredGid,
                                       @RequestHeader(value = "storeCode", required = false) String ignoredShop) {
        return Results.success(authService.listShops());
    }

    @PostMapping
    public Result<ShopView> create(@Valid @RequestBody CreateShopRequest request) {
        return Results.success(authService.createShop(request));
    }

    @GetMapping("/{storeCode}")
    public Result<ShopView> get(@PathVariable String storeCode) {
        return Results.success(authService.getShop(storeCode));
    }
}
