package com.sayohno.marketing.campaign.web;

import com.sayohno.marketing.campaign.service.CampaignFlowService;
import com.sayohno.marketing.campaign.service.CampaignService;
import com.sayohno.marketing.campaign.service.CampaignCreateService;
import com.sayohno.marketing.campaign.web.dto.ActivityRequest;
import com.sayohno.marketing.campaign.web.dto.AttachOfferRequest;
import com.sayohno.marketing.campaign.web.dto.ChannelRequest;
import com.sayohno.marketing.campaign.web.dto.EnabledRequest;
import com.sayohno.marketing.campaign.web.dto.OfferRequest;
import com.sayohno.marketing.campaign.web.dto.PlaceRequest;
import com.sayohno.marketing.common.result.Result;
import com.sayohno.marketing.common.result.Results;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/merchant/activities")
public class CampaignController {
    private final CampaignService campaignService;
    private final CampaignFlowService flowService;
    private final CampaignCreateService createService;

    public CampaignController(CampaignService campaignService, CampaignFlowService flowService, CampaignCreateService createService) {
        this.campaignService = campaignService;
        this.flowService = flowService;
        this.createService = createService;
    }

    @GetMapping
    public Result<?> list() {
        return Results.success(flowService.listActivitiesSummary());
    }

    @GetMapping("/{id}")
    public Result<?> detail(@PathVariable long id) {
        var activity = campaignService.requireOwned(id);
        Map<String, Object> body = campaignService.present(activity);
        body.put("reservations", flowService.activeReservations(id));
        body.put("claims", flowService.attributedClaims(id));
        body.put("visits", flowService.effectiveVisitors(id));
        body.put("channels", flowService.channels(id).stream().map(channel -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", channel.getId());
            row.put("name", channel.getName());
            row.put("channelCode", channel.getChannelCode());
            row.put("linkId", channel.getLinkId());
            row.put("status", channel.getStatus());
            return row;
        }).toList());
        body.put("mainLink", flowService.mainLink(id));
        return Results.success(body);
    }

    @PatchMapping("/{id}")
    public Result<?> update(@PathVariable long id, @Valid @RequestBody ActivityRequest request) {
        return Results.success(campaignService.update(id, request.getName(), request.getWarmupStartTime(),
                request.getStartTime(), request.getEndTime(), request.getVersion(), request.getActivityType(), request.getBudgetAmount()));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable long id) {
        flowService.deleteDraft(id);
        return Results.success();
    }

    @PostMapping("/{id}/copy")
    public Result<?> copy(@PathVariable long id) {
        long created = flowService.copy(id);
        return Results.success(campaignService.present(campaignService.requireOwned(created)));
    }

    @PostMapping
    public Result<?> create(@Valid @RequestBody ActivityRequest request) {
        return Results.success(createService.create(request));
    }

    @PostMapping("/{id}/offers")
    public Result<?> attach(@PathVariable long id, @Valid @RequestBody AttachOfferRequest request) {
        return Results.success(campaignService.attachCoupon(id, request.getPlanId()));
    }

    @GetMapping("/{id}/main-link")
    public Result<?> mainLink(@PathVariable long id) {
        return Results.success(flowService.mainLink(id));
    }

    @GetMapping("/{id}/checks")
    public Result<?> checks(@PathVariable long id) {
        return Results.success(campaignService.checks(id));
    }

    @PostMapping("/{id}/allocation")
    public Result<?> place(@PathVariable long id, @Valid @RequestBody PlaceRequest request) {
        return Results.success(flowService.place(id, request.getQuantity(), request.getMaxClaimsPerUser(),
                request.getIdempotencyKey(), request.getBudgetAmount()));
    }

    @PutMapping("/{id}/allocation")
    public Result<?> saveOffer(@PathVariable long id, @Valid @RequestBody OfferRequest request) {
        return Results.success(flowService.saveOffer(id, request.getPlanId(), request.getQuantity(),
                request.getMaxClaimsPerUser(), request.getIdempotencyKey(), request.getBudgetAmount(), request.getVersion()));
    }

    @PostMapping("/{id}/main")
    public Result<?> main(@PathVariable long id, @Valid @RequestBody EnabledRequest request) {
        return Results.success(campaignService.setMainEnabled(id, request.getEnabled()));
    }

    @PostMapping("/{id}/main-link")
    public Result<?> openMain(@PathVariable long id) {
        return Results.success(flowService.openMain(id));
    }

    @PostMapping("/{id}/publish")
    public Result<?> publish(@PathVariable long id) {
        return Results.success(campaignService.publish(id));
    }

    @PostMapping("/{id}/offline")
    public Result<?> offline(@PathVariable long id) {
        return Results.success(campaignService.offline(id));
    }

    @PostMapping("/{id}/terminate")
    public Result<?> terminate(@PathVariable long id) {
        return Results.success(campaignService.cancel(id));
    }

    @PostMapping("/{id}/pause")
    public Result<?> pause(@PathVariable long id) {
        return Results.success(campaignService.pause(id));
    }

    @PostMapping("/{id}/cancel")
    public Result<?> cancel(@PathVariable long id) {
        return Results.success(campaignService.cancel(id));
    }

    @PostMapping("/{id}/postpone")
    public Result<?> postpone(@PathVariable long id, @Valid @RequestBody ActivityRequest request) {
        return Results.success(campaignService.postpone(id, request.getWarmupStartTime(),
                request.getStartTime(), request.getEndTime()));
    }

    @PostMapping("/{id}/channels")
    public Result<?> channel(@PathVariable long id, @Valid @RequestBody ChannelRequest request) {
        return Results.success(flowService.addChannel(id, request.getName(), request.getIdempotencyKey(), request.getChannelCode()));
    }

    @PostMapping("/{id}/channels/{channelId}/status")
    public Result<?> channelStatus(@PathVariable long id, @PathVariable long channelId, @Valid @RequestBody EnabledRequest request) {
        return Results.success(flowService.setChannelEnabled(id, channelId, request.getEnabled()));
    }

    @GetMapping("/{id}/channels")
    public Result<?> channels(@PathVariable long id) {
        return Results.success(flowService.channels(id));
    }

    @GetMapping("/{id}/reservations/count")
    public Result<?> reservations(@PathVariable long id) {
        return Results.success(flowService.activeReservations(id));
    }

    @GetMapping("/{id}/report")
    public Result<?> report(@PathVariable long id) {
        return Results.success(flowService.report(id));
    }

    @GetMapping("/{id}/dashboard")
    public Result<?> dashboard(@PathVariable long id) {
        return Results.success(flowService.dashboard(id));
    }

    @GetMapping("/{id}/review")
    public Result<?> review(@PathVariable long id) {
        return Results.success(flowService.review(id));
    }
}
