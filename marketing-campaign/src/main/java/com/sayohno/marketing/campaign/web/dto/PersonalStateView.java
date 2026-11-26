package com.sayohno.marketing.campaign.web.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 活动落地页个人预约、领券与参与资格状态视图。
 */
public record PersonalStateView(
        boolean personalReserved,
        long personalClaimed,
        boolean eligible,
        String eligibilityReason
) {
    public Map<String, Object> toMap() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("personalReserved", personalReserved);
        body.put("personalClaimed", personalClaimed);
        body.put("eligible", eligible);
        body.put("eligibilityReason", eligibilityReason == null ? "" : eligibilityReason);
        return body;
    }
}
