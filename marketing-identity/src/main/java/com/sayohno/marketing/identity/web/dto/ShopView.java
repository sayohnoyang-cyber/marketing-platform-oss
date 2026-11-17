package com.sayohno.marketing.identity.web.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ShopView {
    private String id;
    private String merchantId;
    private String storeCode;
    private String name;
    private Integer status;
}
