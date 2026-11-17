package com.sayohno.marketing.campaign.web;

import com.sayohno.marketing.campaign.service.*;
import com.sayohno.marketing.campaign.web.dto.*;
import com.sayohno.marketing.common.result.Result;
import com.sayohno.marketing.common.result.Results;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/merchant")
public class CampaignOperationsController {
    private final CampaignForecastService forecasts;
    private final CampaignCalibrationService calibration;
    private final CampaignMessageService messages;
    private final CampaignOperationStore store;
    private final CapacityBaselineService capacity;
    private final CampaignPreparationService preparation;
    private final CampaignRateLimitService limits;

    public CampaignOperationsController(CampaignForecastService forecasts, CampaignOperationStore store, CapacityBaselineService capacity,
            CampaignPreparationService preparation, CampaignRateLimitService limits, CampaignCalibrationService calibration, CampaignMessageService messages) {
        this.forecasts = forecasts; this.store = store; this.capacity = capacity; this.preparation = preparation; this.limits = limits; this.calibration = calibration; this.messages = messages;
    }

    @GetMapping("/activities/{id}/opening-plan") public Result<?> openingPlan(@PathVariable long id) { return Results.success(forecasts.plan(id)); }
    @GetMapping("/activities/{id}/forecast-calibration") public Result<?> calibration(@PathVariable long id) { return Results.success(calibration.recommend(id)); }
    @GetMapping("/activities/{id}/message-status") public Result<?> messageStatus(@PathVariable long id) { return Results.success(messages.status(id)); }
    @GetMapping("/capacity-baselines") public Result<?> capacity() { return Results.success(capacity.list()); }
    @PostMapping("/capacity-baselines") public Result<?> createCapacity(@Valid @RequestBody CapacityRequest request) { return Results.success(capacity.create(request)); }
    @GetMapping("/activities/{id}/forecasts") public Result<?> forecasts(@PathVariable long id) { return Results.success(store.list(id, "FORECAST")); }
    @PostMapping("/activities/{id}/forecasts") public Result<?> forecast(@PathVariable long id, @Valid @RequestBody ForecastRequest request) { return Results.success(forecasts.save(id, request)); }
    @PostMapping({"/activities/{id}/prepare-runs", "/activities/{id}/preparation-runs"}) public Result<?> prepare(@PathVariable long id, @Valid @RequestBody PreparationRequest request) { return Results.success(preparation.run(id, request.requestKey())); }
    @GetMapping("/activities/{id}/prepare-runs") public Result<?> preparations(@PathVariable long id) { return Results.success(store.list(id, "PREPARE")); }
    @GetMapping("/activities/{id}/prepare-runs/{runId}") public Result<?> preparation(@PathVariable long id, @PathVariable long runId) { return Results.success(store.present(store.require(id, runId, "PREPARE"))); }
    @PutMapping("/activities/{id}/rate-limit-policy") public Result<?> apply(@PathVariable long id, @Valid @RequestBody ApplyLimitRequest request) { return Results.success(limits.apply(id, request)); }
    @GetMapping("/activities/{id}/rate-limit-policy") public Result<?> policy(@PathVariable long id) { return Results.success(limits.current(id)); }
    @DeleteMapping("/activities/{id}/rate-limit-policy") public Result<?> disable(@PathVariable long id) { limits.disable(id); return Results.success(); }
}
