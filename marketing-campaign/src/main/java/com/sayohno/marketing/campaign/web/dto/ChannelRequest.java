package com.sayohno.marketing.campaign.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChannelRequest {
    @NotBlank
    private String name;
    @NotBlank
    private String idempotencyKey;
    @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9]{2,12}")
    private String channelCode;
}
