package com.sayohno.marketing.common.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

/** Browser identity shared by redirects and activity pages. Never trusts request-body identities. */
public final class VisitorIdentity {
    private VisitorIdentity() { }

    public static String resolve(HttpServletRequest request, HttpServletResponse response) {
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if ("marketing_visitor".equals(cookie.getName()) && cookie.getValue() != null && cookie.getValue().matches("[A-Za-z0-9_-]{16,64}")) {
                    return cookie.getValue();
                }
            }
        }
        String visitor = UUID.randomUUID().toString().replace("-", "");
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from("marketing_visitor", visitor)
                .path("/").httpOnly(true).secure(request.isSecure()).sameSite("Lax")
                .maxAge(Duration.ofDays(365)).build().toString());
        return visitor;
    }

    public static boolean humanCandidate(HttpServletRequest request) {
        String agent = request.getHeader(HttpHeaders.USER_AGENT);
        if (agent == null) return true;
        String value = agent.toLowerCase(Locale.ROOT);
        return !value.matches(".*(bot|spider|crawler|slurp).*");
    }
}
