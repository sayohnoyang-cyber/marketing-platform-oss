package com.sayohno.marketing.identity.web.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ProfileView {
    private String userId;
    private String username;
    private String merchantId;
    private String storeCode;
    private List<String> roles;
}
