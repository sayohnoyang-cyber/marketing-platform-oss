package com.sayohno.marketing.campaign.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class PlaceRequest {
    @NotNull
    @Min(1)
    private Integer quantity;
    @NotNull
    @Min(1)
    private Integer maxClaimsPerUser;
    @NotBlank
    private String idempotencyKey;
    private BigDecimal budgetAmount;
}
