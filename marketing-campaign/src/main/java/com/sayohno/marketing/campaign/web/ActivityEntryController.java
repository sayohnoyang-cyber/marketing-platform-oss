package com.sayohno.marketing.campaign.web;

import com.sayohno.marketing.campaign.service.CampaignFlowService;
import com.sayohno.marketing.campaign.web.dto.ActivityClaimRequest;
import com.sayohno.marketing.campaign.web.dto.EntryRequest;
import com.sayohno.marketing.common.context.UserContext;
import com.sayohno.marketing.common.result.Result;
import com.sayohno.marketing.common.result.Results;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.sayohno.marketing.common.web.VisitorIdentity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ActivityEntryController {
    private final CampaignFlowService flowService;

    public ActivityEntryController(CampaignFlowService flowService) {
        this.flowService = flowService;
    }

    @PostMapping("/api/public/activities/entry")
    public Result<?> entry(@Valid @RequestBody EntryRequest request, HttpServletRequest http, HttpServletResponse response) {
        return Results.success(identify(flowService.entry(request.requiredShortUri(), request.getChannelCode(), VisitorIdentity.resolve(http, response), VisitorIdentity.humanCandidate(http))));
    }

    @PostMapping("/api/public/activities/{id}/visit")
    public Result<?> visit(@PathVariable long id, @RequestBody EntryRequest request, HttpServletRequest http, HttpServletResponse response) {
        return Results.success(identify(flowService.visit(id, request.getChannelCode(), VisitorIdentity.resolve(http, response), VisitorIdentity.humanCandidate(http))));
    }

    private java.util.Map<String, Object> identify(java.util.Map<String, Object> body) {
        var user = UserContext.get();
        if (user != null) {
            long activityId = ((Number) body.get("activityId")).longValue();
            if (body.get("credential") != null) {
                flowService.identifyVisitor(activityId, body.get("credential").toString(), user.getUserId());
            }
            body.putAll(flowService.personalState(activityId, user.getUserId()));
        } else {
            body.put("personalReserved", false);
            body.put("personalClaimed", 0L);
        }
        return body;
    }

    @PostMapping("/api/public/activities/{id}/status")
    public Result<?> status(@PathVariable long id, @RequestBody EntryRequest request) {
        // 轮询只读取状态，不生成访问凭证或访问记录，避免等待开场时放大统计。
        return Results.success(identify(flowService.visit(id, request.getChannelCode(), null, false)));
    }

    @PostMapping("/api/user/activities/{id}/reservations")
    public Result<?> reserve(@PathVariable long id, @Valid @RequestBody EntryRequest request) {
        return Results.success(flowService.reserve(UserContext.require().getUserId(), id, request.getCredential()));
    }

    @PostMapping("/api/user/activities/{id}/reservations/cancel")
    public Result<?> cancel(@PathVariable long id) {
        return Results.success(flowService.cancelReservation(UserContext.require().getUserId(), id));
    }

    @PostMapping("/api/user/activities/{id}/claims")
    public Result<?> claim(@PathVariable long id, @Valid @RequestBody ActivityClaimRequest request) {
        return Results.success(flowService.claim(UserContext.require().getUserId(), id,
                request.getPlanId(), request.getRequestId(), request.getCredential()));
    }

    @PostMapping("/api/user/activities/{id}/claims/async")
    public Result<?> claimAsync(@PathVariable long id, @Valid @RequestBody ActivityClaimRequest request) {
        return Results.success(flowService.claimAsync(UserContext.require().getUserId(), id,
                request.getPlanId(), request.getRequestId(), request.getCredential()));
    }

    @GetMapping("/api/user/activities/{id}/claims/{requestId}")
    public Result<?> claimStatus(@PathVariable long id,
                                 @PathVariable String requestId,
                                 @RequestParam long planId) {
        return Results.success(flowService.claimStatus(UserContext.require().getUserId(), id, planId, requestId));
    }
}
