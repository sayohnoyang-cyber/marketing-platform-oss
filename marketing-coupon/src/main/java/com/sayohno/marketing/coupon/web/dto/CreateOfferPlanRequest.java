package com.sayohno.marketing.coupon.web.dto;

import com.sayohno.marketing.coupon.enums.ApplyScope;
import com.sayohno.marketing.coupon.enums.BenefitKind;
import com.sayohno.marketing.coupon.enums.IssuerScope;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CreateOfferPlanRequest {
    private String storeCode;
    @NotBlank
    private String title;
    @NotNull
    private IssuerScope issuerScope;
    @NotNull
    private ApplyScope applyScope;
    private String skuCode;
    @NotNull
    private BenefitKind benefitKind;
    @NotNull
    @Min(0)
    private Integer inventoryQty;
    @NotNull
    private LocalDateTime usableFrom;
    @NotNull
    private LocalDateTime usableUntil;
    @NotBlank
    private String claimPolicy;
    @NotBlank
    private String benefitPolicy;
}
