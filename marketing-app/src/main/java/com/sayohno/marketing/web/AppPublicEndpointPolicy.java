package com.sayohno.marketing.web;

import com.sayohno.marketing.common.web.PublicEndpointPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * 应用入口层公开端点声明：放行健康检查与默认错误页。
 */
@Component
public class AppPublicEndpointPolicy implements PublicEndpointPolicy {

    @Override
    public boolean isPublic(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "/api/health".equals(path)
                || "/actuator/health".equals(path)
                || path.startsWith("/actuator/health/")
                || "/error".equals(path);
    }
}
