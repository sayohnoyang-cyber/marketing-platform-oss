package com.sayohno.marketing.campaign.web.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AttachOfferRequest {
    @NotNull
    private Long planId;
}
