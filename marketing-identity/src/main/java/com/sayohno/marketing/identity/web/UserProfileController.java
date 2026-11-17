package com.sayohno.marketing.identity.web;

import com.sayohno.marketing.common.result.Result;
import com.sayohno.marketing.common.result.Results;
import com.sayohno.marketing.identity.service.AuthService;
import com.sayohno.marketing.identity.web.dto.ProfileView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user")
public class UserProfileController {
    private final AuthService authService;

    public UserProfileController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/profile")
    public Result<ProfileView> profile(@RequestHeader(value = "username", required = false) String ignoredUsername,
                                       @RequestHeader(value = "userId", required = false) String ignoredUserId,
                                       @RequestHeader(value = "merchantId", required = false) String ignoredMerchantId) {
        return Results.success(authService.profile());
    }
}
