package com.sayohno.marketing.identity.web;

import com.sayohno.marketing.common.result.Result;
import com.sayohno.marketing.common.result.Results;
import com.sayohno.marketing.identity.service.AuthService;
import com.sayohno.marketing.identity.web.dto.AddMemberRequest;
import com.sayohno.marketing.identity.web.dto.MemberView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/merchant/members")
public class MerchantMemberController {
    private final AuthService authService;

    public MerchantMemberController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping
    public Result<List<MemberView>> list() {
        return Results.success(authService.listMembers());
    }

    @PostMapping
    public Result<MemberView> add(@Valid @RequestBody AddMemberRequest request) {
        return Results.success(authService.addMember(request));
    }
}
