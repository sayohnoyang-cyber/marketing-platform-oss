package com.sayohno.marketing.identity.web.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MembershipView {
    private String merchantId;
    private String merchantName;
    private String role;
}
