package com.sayohno.marketing.identity.web.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class LoginResponse {
    private String token;
    private String userId;
    private String username;
    private String merchantId;
    private String storeCode;
    private List<String> roles;
    private List<MembershipView> memberships;
}
