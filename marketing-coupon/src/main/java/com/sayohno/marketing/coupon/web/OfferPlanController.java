package com.sayohno.marketing.coupon.web;

import com.sayohno.marketing.common.result.Result;
import com.sayohno.marketing.common.result.Results;
import com.sayohno.marketing.coupon.dao.entity.OfferPlan;
import com.sayohno.marketing.coupon.service.OfferInventoryService;
import com.sayohno.marketing.coupon.service.OfferPlanService;
import com.sayohno.marketing.coupon.web.dto.CreateOfferPlanRequest;
import com.sayohno.marketing.coupon.web.dto.IncreaseInventoryRequest;
import com.sayohno.marketing.coupon.web.dto.ReviseOfferPlanRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/merchant/offer-plans")
public class OfferPlanController {
    private final OfferPlanService templateService;
    private final OfferInventoryService allocationService;

    public OfferPlanController(OfferPlanService templateService, OfferInventoryService allocationService) {
        this.templateService = templateService;
        this.allocationService = allocationService;
    }

    @PostMapping
    public Result<OfferPlan> create(@Valid @RequestBody CreateOfferPlanRequest request) {
        return Results.success(templateService.create(request.getStoreCode(), request.getTitle(), request.getIssuerScope(),
                request.getApplyScope(), request.getSkuCode(), request.getBenefitKind(), request.getInventoryQty(),
                request.getUsableFrom(), request.getUsableUntil(), request.getClaimPolicy(), request.getBenefitPolicy()));
    }

    @GetMapping
    public Result<?> page(@RequestParam(defaultValue = "1") long page, @RequestParam(defaultValue = "20") long size) {
        var result = templateService.page(page, size);
        result.getRecords().forEach(allocationService::fillUsage);
        return Results.success(result);
    }

    @GetMapping("/{id}")
    public Result<OfferPlan> detail(@PathVariable long id) {
        return Results.success(templateService.requireOwned(id));
    }

    @PutMapping("/{id}")
    public Result<OfferPlan> revise(@PathVariable long id, @RequestBody ReviseOfferPlanRequest request) {
        return Results.success(templateService.revise(id, request.getTitle(), request.getUsableFrom(),
                request.getUsableUntil(), request.getClaimPolicy(), request.getBenefitPolicy()));
    }

    @PostMapping("/{id}/stock")
    public Result<OfferPlan> stock(@PathVariable long id, @Valid @RequestBody IncreaseInventoryRequest request) {
        return Results.success(templateService.increaseStock(id, request.getCount()));
    }

    @PostMapping("/{id}/terminate")
    public Result<OfferPlan> terminate(@PathVariable long id) {
        return Results.success(templateService.terminate(id));
    }
}
