package com.sayohno.marketing.identity.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sayohno.marketing.common.config.MarketingProperties;
import com.sayohno.marketing.common.context.CurrentUser;
import com.sayohno.marketing.common.context.UserContext;
import com.sayohno.marketing.common.result.ErrorCode;
import com.sayohno.marketing.common.result.RequestIds;
import com.sayohno.marketing.common.result.Result;
import com.sayohno.marketing.common.result.Results;
import com.sayohno.marketing.common.web.PublicEndpointPolicy;
import com.sayohno.marketing.identity.service.IdentityContextLoader;
import com.sayohno.marketing.identity.session.SessionStore;
import com.sayohno.marketing.identity.session.UserSession;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AuthenticationFilter extends OncePerRequestFilter {
    private final SessionStore sessionStore;
    private final IdentityContextLoader contextLoader;
    private final ObjectMapper objectMapper;
    private final MarketingProperties properties;
    private final List<PublicEndpointPolicy> publicPolicies;

    /**
     * 仅供未经过 Spring 容器装配的单元测试构造器使用；生产环境下由各模块通过 PublicEndpointPolicy SPI 自动注入。
     */
    public AuthenticationFilter(SessionStore sessionStore,
                                IdentityContextLoader contextLoader,
                                ObjectMapper objectMapper,
                                MarketingProperties properties) {
        this(sessionStore, contextLoader, objectMapper, properties, defaultTestPolicies());
    }

    @Autowired
    public AuthenticationFilter(SessionStore sessionStore,
                                IdentityContextLoader contextLoader,
                                ObjectMapper objectMapper,
                                MarketingProperties properties,
                                ObjectProvider<List<PublicEndpointPolicy>> policiesProvider) {
        this(sessionStore, contextLoader, objectMapper, properties,
                policiesProvider == null ? List.of() : policiesProvider.getIfAvailable(List::of));
    }

    public AuthenticationFilter(SessionStore sessionStore,
                                IdentityContextLoader contextLoader,
                                ObjectMapper objectMapper,
                                MarketingProperties properties,
                                List<PublicEndpointPolicy> publicPolicies) {
        this.sessionStore = sessionStore;
        this.contextLoader = contextLoader;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.publicPolicies = publicPolicies == null ? List.of() : List.copyOf(publicPolicies);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = request.getHeader(RequestIds.HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString().replace("-", "");
        }
        MDC.put(RequestIds.MDC_KEY, requestId);
        response.setHeader(RequestIds.HEADER, requestId);
        try {
            if (isPublic(request)) {
                String optionalToken = supportsOptionalAuth(request) ? readToken(request) : null;
                if (optionalToken != null) {
                    UserSession session = sessionStore.find(optionalToken).orElse(null);
                    Duration renewTtl = session == null ? null : effectiveTtl(session);
                    if (session != null && renewTtl == null) {
                        sessionStore.delete(optionalToken);
                    } else if (session != null) {
                        CurrentUser current = contextLoader.load(session);
                        if (current != null) {
                            sessionStore.touch(optionalToken, renewTtl);
                            UserContext.set(current);
                        }
                    }
                }
                chain.doFilter(request, response);
                return;
            }
            String token = readToken(request);
            if (token == null) {
                write(response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.UNAUTHORIZED);
                return;
            }
            UserSession session = sessionStore.find(token).orElse(null);
            Duration renewTtl = session == null ? null : effectiveTtl(session);
            if (session != null && renewTtl == null) {
                sessionStore.delete(token);
                write(response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.UNAUTHORIZED);
                return;
            }
            CurrentUser current = session == null ? null : contextLoader.load(session);
            if (current == null) {
                sessionStore.delete(token);
                write(response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.UNAUTHORIZED);
                return;
            }
            if (isMerchantPath(request) && !current.hasMerchantAccess()) {
                write(response, HttpServletResponse.SC_FORBIDDEN, ErrorCode.FORBIDDEN);
                return;
            }
            sessionStore.touch(token, renewTtl);
            request.setAttribute(AuthAttributes.TOKEN, token);
            UserContext.set(current);
            try {
                chain.doFilter(request, response);
            } finally {
                UserContext.clear();
            }
        } finally {
            UserContext.clear();
            MDC.remove(RequestIds.MDC_KEY);
        }
    }

    private boolean isPublic(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        return publicPolicies.stream().anyMatch(policy -> policy.isPublic(request));
    }

    private boolean supportsOptionalAuth(HttpServletRequest request) {
        return publicPolicies.stream().anyMatch(policy -> policy.supportsOptionalAuth(request));
    }

    private static List<PublicEndpointPolicy> defaultTestPolicies() {
        return List.of(
                new IdentityPublicEndpointPolicy(),
                new PublicEndpointPolicy() {
                    @Override
                    public boolean isPublic(HttpServletRequest request) {
                        String path = request.getRequestURI();
                        String method = request.getMethod();
                        return "/api/health".equals(path)
                                || "/error".equals(path)
                                || ("GET".equalsIgnoreCase(method) && (path.startsWith("/s/") || path.matches("/a/\\d+")))
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
        );
    }

    private boolean isMerchantPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "/api/merchant".equals(path) || path.startsWith("/api/merchant/");
    }

    private String readToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = authorization.substring(7).trim();
            return token.isEmpty() ? null : token;
        }
        String token = request.getHeader("Token");
        if (token == null || token.isBlank()) {
            return null;
        }
        return token.trim();
    }

    private Duration effectiveTtl(UserSession session) {
        Duration ttl = properties.getSession().getTtl();
        Duration maxLifetime = properties.getSession().getMaxLifetime();
        if (session.getLoginAtEpochSeconds() <= 0 || maxLifetime == null || maxLifetime.isZero() || maxLifetime.isNegative()) {
            return ttl;
        }
        long elapsedSeconds = Math.max(0L, Instant.now().getEpochSecond() - session.getLoginAtEpochSeconds());
        Duration remaining = maxLifetime.minusSeconds(elapsedSeconds);
        if (remaining.isZero() || remaining.isNegative()) {
            return null;
        }
        return remaining.compareTo(ttl) < 0 ? remaining : ttl;
    }

    private void write(HttpServletResponse response, int status, ErrorCode errorCode) throws IOException {
        Result<Void> body = Results.failure(errorCode);
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
