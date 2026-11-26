package com.sayohno.marketing.common.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 公开端点与可选鉴权路由策略 SPI：
 * 由各业务模块自行注册声明所属公开路径，避免底层 identity 鉴权过滤器反向硬编码上层模块路由。
 */
public interface PublicEndpointPolicy {

    /**
     * 判断当前请求是否无需登录即可放行。
     */
    boolean isPublic(HttpServletRequest request);

    /**
     * 判断公开请求是否允许在携带合法 Token 时顺带解析并注入当前用户上下文（例如活动落地页透出个人预约/领取状态）。
     */
    default boolean supportsOptionalAuth(HttpServletRequest request) {
        return false;
    }
}
