package com.sayohno.marketing.campaign.web;

import com.sayohno.marketing.common.web.PublicEndpointPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * 活动模块公开端点声明：放行直达页重定向与公开访问/状态接口，并支持可选用户态识别个人预约与限领进度。
 */
@Component
public class CampaignPublicEndpointPolicy implements PublicEndpointPolicy {

    @Override
    public boolean isPublic(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        return ("GET".equalsIgnoreCase(method) && path.matches("/a/\\d+"))
                || "/api/public/activities/entry".equals(path)
                || ("POST".equalsIgnoreCase(method) && path.matches("/api/public/activities/\\d+/(visit|status)"));
    }

    @Override
    public boolean supportsOptionalAuth(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "/api/public/activities/entry".equals(path)
                || path.matches("/api/public/activities/\\d+/(visit|status)");
    }
}
