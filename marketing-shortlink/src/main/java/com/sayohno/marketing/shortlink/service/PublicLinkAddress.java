package com.sayohno.marketing.shortlink.service;

import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.common.result.ErrorCode;

public final class PublicLinkAddress {
    private PublicLinkAddress() {
    }

    public static String base(String configuredBase, boolean trustForwarded, String forwardedHost, String forwardedProto) {
        String base = trim(configuredBase);
        if (!trustForwarded || forwardedHost == null || !forwardedHost.matches("[A-Za-z0-9.-]+(:\\d+)?")) {
            return base;
        }
        String proto = "https".equalsIgnoreCase(forwardedProto) ? "https" : "http";
        return proto + "://" + forwardedHost;
    }

    public static String host(String base) {
        String value = trim(base);
        int scheme = value.indexOf("://");
        if (scheme < 0) {
            throw new BizException(ErrorCode.VALIDATION, "短链公开地址必须包含协议");
        }
        String host = value.substring(scheme + 3);
        int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        if (host.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION, "短链公开地址没有主机名");
        }
        return host;
    }

    public static String full(String base, String shortUri) {
        return host(base) + "/s/" + shortUri;
    }

    private static String trim(String value) {
        if (value == null || value.isBlank()) {
            throw new BizException(ErrorCode.VALIDATION, "短链公开地址未配置");
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
