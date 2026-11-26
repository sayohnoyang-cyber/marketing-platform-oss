package com.sayohno.marketing.identity.web;

import com.sayohno.marketing.common.web.PublicEndpointPolicy;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * 身份认证模块公开端点声明：仅放行登录与注册接口。
 */
@Component
public class IdentityPublicEndpointPolicy implements PublicEndpointPolicy {

    @Override
    public boolean isPublic(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "/api/identity/login".equals(path) || "/api/identity/register".equals(path);
    }
}
