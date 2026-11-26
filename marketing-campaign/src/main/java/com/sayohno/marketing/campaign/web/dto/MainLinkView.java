package com.sayohno.marketing.campaign.web.dto;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 活动主推广短链强类型视图。
 */
public record MainLinkView(
        Long id,
        boolean enabled,
        String shortUri,
        String fullShortUrl,
        String originUrl
) {
    public Map<String, Object> toMap() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", enabled);
        if (id != null) {
            body.put("id", id);
        }
        body.put("shortUri", shortUri == null ? "" : shortUri);
        body.put("fullShortUrl", fullShortUrl == null ? "" : fullShortUrl);
        body.put("originUrl", originUrl == null ? "" : originUrl);
        return body;
    }
}
