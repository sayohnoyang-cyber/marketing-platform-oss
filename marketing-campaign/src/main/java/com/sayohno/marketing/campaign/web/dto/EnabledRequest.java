package com.sayohno.marketing.campaign.web.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class EnabledRequest {
    @NotNull
    private Boolean enabled;
}
