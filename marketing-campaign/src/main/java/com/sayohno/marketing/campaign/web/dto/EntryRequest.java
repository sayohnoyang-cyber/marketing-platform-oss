package com.sayohno.marketing.campaign.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class EntryRequest {
    private String shortUri;
    private String channelCode;
    private String visitorId;
    private String credential;

    public String requiredShortUri() {
        if (shortUri == null || shortUri.isBlank()) {
            throw new com.sayohno.marketing.common.exception.BizException(
                    com.sayohno.marketing.common.result.ErrorCode.VALIDATION, "短码不能为空");
        }
        return shortUri;
    }
}
