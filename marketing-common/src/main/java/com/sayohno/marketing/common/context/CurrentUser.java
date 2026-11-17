package com.sayohno.marketing.common.context;

import lombok.Builder;
import lombok.Getter;

import java.util.Set;

@Getter
@Builder
public class CurrentUser {
    private final Long userId;
    private final String username;
    private final Long merchantId;
    private final String storeCode;
    private final Set<String> roles;

    public boolean hasMerchantAccess() {
        return merchantId != null && roles != null && roles.stream().anyMatch(UserRole::merchantRole);
    }

    public boolean owner() {
        return roles != null && roles.contains(UserRole.OWNER);
    }
}
