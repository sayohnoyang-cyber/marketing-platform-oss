package com.sayohno.marketing.campaign.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ActivityClaimRequest {
    @NotNull
    private Long planId;
    @NotBlank
    private String requestId;
    @NotBlank
    private String credential;
}
