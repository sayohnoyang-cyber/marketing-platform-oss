package com.sayohno.marketing.identity.web.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SwitchShopRequest {
    @NotBlank
    private String storeCode;
}
