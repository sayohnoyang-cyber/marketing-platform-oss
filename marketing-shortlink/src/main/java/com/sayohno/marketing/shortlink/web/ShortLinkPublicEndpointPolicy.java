package com.sayohno.marketing.shortlink.web;

import com.sayohno.marketing.common.web.PublicEndpointPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * 短链模块公开端点声明：放行公开短链 302 跳转路由 GET /s/**。
 */
@Component
public class ShortLinkPublicEndpointPolicy implements PublicEndpointPolicy {

    @Override
    public boolean isPublic(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "GET".equalsIgnoreCase(request.getMethod()) && path.startsWith("/s/");
    }
}
