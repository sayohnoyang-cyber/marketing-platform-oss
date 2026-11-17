package com.sayohno.marketing.identity.web;

import com.sayohno.marketing.common.result.Result;
import com.sayohno.marketing.common.result.Results;
import com.sayohno.marketing.identity.service.AuthService;
import com.sayohno.marketing.identity.web.dto.LoginRequest;
import com.sayohno.marketing.identity.web.dto.LoginResponse;
import com.sayohno.marketing.identity.web.dto.ProfileView;
import com.sayohno.marketing.identity.web.dto.RegisterRequest;
import com.sayohno.marketing.identity.web.dto.SwitchMerchantRequest;
import com.sayohno.marketing.identity.web.dto.SwitchShopRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/identity")
public class IdentityController {
    private final AuthService authService;

    public IdentityController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public Result<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        return Results.success(authService.register(request));
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Results.success(authService.login(request));
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        authService.logout((String) request.getAttribute(AuthAttributes.TOKEN));
        return Results.success();
    }

    @GetMapping("/me")
    public Result<ProfileView> me() {
        return Results.success(authService.profile());
    }

    @PostMapping("/switch-merchant")
    public Result<LoginResponse> switchMerchant(@Valid @RequestBody SwitchMerchantRequest request,
                                                HttpServletRequest httpRequest) {
        return Results.success(authService.switchMerchant(token(httpRequest), request.getMerchantId()));
    }

    @PostMapping("/switch-shop")
    public Result<LoginResponse> switchShop(@Valid @RequestBody SwitchShopRequest request,
                                            HttpServletRequest httpRequest) {
        return Results.success(authService.switchShop(token(httpRequest), request.getStoreCode()));
    }

    private String token(HttpServletRequest request) {
        return (String) request.getAttribute(AuthAttributes.TOKEN);
    }
}
