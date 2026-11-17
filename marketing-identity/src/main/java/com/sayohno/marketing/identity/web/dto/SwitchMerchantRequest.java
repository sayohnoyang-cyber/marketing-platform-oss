package com.sayohno.marketing.identity.web.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SwitchMerchantRequest {
    @NotNull
    private Long merchantId;
}
