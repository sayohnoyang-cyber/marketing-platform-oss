package com.sayohno.marketing.coupon.web.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class IncreaseInventoryRequest {
    @NotNull
    @Min(1)
    private Integer count;
}
