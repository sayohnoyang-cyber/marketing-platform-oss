package com.sayohno.marketing.identity.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sayohno.marketing.common.config.MarketingProperties;
import com.sayohno.marketing.common.context.CurrentUser;
import com.sayohno.marketing.common.context.UserContext;
import com.sayohno.marketing.common.context.UserRole;
import com.sayohno.marketing.identity.service.IdentityContextLoader;
import com.sayohno.marketing.identity.session.SessionStore;
import com.sayohno.marketing.identity.session.UserSession;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthenticationFilterTest {

    @AfterEach
    void clear() {
        UserContext.clear();
    }

    @Test
    void consecutiveUsersDoNotLeakAndHeadersAreIgnored() throws Exception {
        SessionStore store = new SessionStore() {
            @Override
            public void save(String token, UserSession session, Duration ttl) {
            }

            @Override
            public Optional<UserSession> find(String token) {
                UserSession session = new UserSession();
                session.setUserId("token-a".equals(token) ? 1L : 2L);
                return Optional.of(session);
            }

            @Override
            public void delete(String token) {
            }
        };
        IdentityContextLoader loader = mock(IdentityContextLoader.class);
        when(loader.load(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            UserSession session = invocation.getArgument(0);
            return CurrentUser.builder()
                    .userId(session.getUserId())
                    .username(session.getUserId() == 1L ? "merchant-a" : "merchant-b")
                    .roles(Set.of(UserRole.USER, UserRole.OWNER))
                    .merchantId(session.getUserId())
                    .build();
        });
        AuthenticationFilter filter = new AuthenticationFilter(store, loader, new ObjectMapper(), new MarketingProperties());
        AtomicReference<String> seen = new AtomicReference<>();
        FilterChain chain = (request, response) -> {
            seen.set(UserContext.get().getUsername());
            assertEquals(((jakarta.servlet.http.HttpServletRequest) request).getHeader("username"),
                    request.getAttribute("ignored") == null ? ((jakarta.servlet.http.HttpServletRequest) request).getHeader("username") : null);
        };

        MockHttpServletRequest first = new MockHttpServletRequest("GET", "/api/user/profile");
        first.addHeader("Authorization", "Bearer token-a");
        first.addHeader("username", "merchant-b");
        first.addHeader("userId", "2");
        first.addHeader("merchantId", "2");
        filter.doFilter(first, new MockHttpServletResponse(), (request, response) -> seen.set(UserContext.require().getUsername()));
        assertEquals("merchant-a", seen.get());
        assertNull(UserContext.get());

        MockHttpServletRequest second = new MockHttpServletRequest("GET", "/api/user/profile");
        second.addHeader("Authorization", "Bearer token-b");
        filter.doFilter(second, new MockHttpServletResponse(), (request, response) -> seen.set(UserContext.require().getUsername()));
        assertEquals("merchant-b", seen.get());
        assertNull(UserContext.get());

        for (String path : new String[]{"/api/public/activities/123/visit", "/api/public/activities/123/status"}) {
            MockHttpServletRequest publicStatus = new MockHttpServletRequest("POST", path);
            publicStatus.addHeader("Authorization", "Bearer token-a");
            filter.doFilter(publicStatus, new MockHttpServletResponse(), (request, response) -> seen.set(UserContext.require().getUsername()));
            assertEquals("merchant-a", seen.get());
            assertNull(UserContext.get());
            filter.doFilter(new MockHttpServletRequest("POST", path), new MockHttpServletResponse(), (request, response) -> assertNull(UserContext.get()));
        }

        MockHttpServletRequest anonymous = new MockHttpServletRequest("GET", "/api/user/profile");
        anonymous.addHeader("username", "merchant-a");
        anonymous.addHeader("userId", "1");
        anonymous.addHeader("merchantId", "1");
        MockHttpServletResponse denied = new MockHttpServletResponse();
        filter.doFilter(anonymous, denied, chain);
        assertEquals(401, denied.getStatus());
        assertNull(UserContext.get());
    }

    @Test
    void activeRequestsRenewWithinMaxLifetimeAndRejectAfterMaxLifetime() throws Exception {
        long now = java.time.Instant.now().getEpochSecond();
        java.util.concurrent.atomic.AtomicReference<Duration> lastTouched = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> deletedToken = new java.util.concurrent.atomic.AtomicReference<>();
        SessionStore store = new SessionStore() {
            @Override
            public void save(String token, UserSession session, Duration ttl) {
            }

            @Override
            public Optional<UserSession> find(String token) {
                UserSession session = new UserSession();
                session.setUserId(1L);
                if ("near-max".equals(token)) {
                    session.setLoginAtEpochSeconds(now - Duration.ofHours(11).plusMinutes(50).toSeconds());
                    return Optional.of(session);
                }
                if ("past-max".equals(token)) {
                    session.setLoginAtEpochSeconds(now - Duration.ofHours(13).toSeconds());
                    return Optional.of(session);
                }
                return Optional.empty();
            }

            @Override
            public void touch(String token, Duration ttl) {
                lastTouched.set(ttl);
            }

            @Override
            public void delete(String token) {
                deletedToken.set(token);
            }
        };
        IdentityContextLoader loader = mock(IdentityContextLoader.class);
        when(loader.load(org.mockito.ArgumentMatchers.any())).thenReturn(
                CurrentUser.builder().userId(1L).username("user").roles(Set.of(UserRole.USER)).build());
        MarketingProperties properties = new MarketingProperties();
        properties.getSession().setTtl(Duration.ofMinutes(30));
        properties.getSession().setMaxLifetime(Duration.ofHours(12));
        AuthenticationFilter filter = new AuthenticationFilter(store, loader, new ObjectMapper(), properties);

        MockHttpServletRequest nearMax = new MockHttpServletRequest("GET", "/api/user/profile");
        nearMax.addHeader("Authorization", "Bearer near-max");
        MockHttpServletResponse ok = new MockHttpServletResponse();
        filter.doFilter(nearMax, ok, (req, res) -> {});
        assertEquals(200, ok.getStatus());
        org.junit.jupiter.api.Assertions.assertTrue(lastTouched.get().toMinutes() <= 10 && lastTouched.get().toMinutes() >= 9);

        MockHttpServletRequest pastMax = new MockHttpServletRequest("GET", "/api/user/profile");
        pastMax.addHeader("Authorization", "Bearer past-max");
        MockHttpServletResponse expired = new MockHttpServletResponse();
        filter.doFilter(pastMax, expired, (req, res) -> {});
        assertEquals(401, expired.getStatus());
        assertEquals("past-max", deletedToken.get());
    }

    @Test
    void honorsInjectedPublicEndpointPolicySpiWithoutHardcodedRoutes() throws Exception {
        SessionStore store = mock(SessionStore.class);
        IdentityContextLoader loader = mock(IdentityContextLoader.class);
        com.sayohno.marketing.common.web.PublicEndpointPolicy customPolicy = req -> "/api/custom-open".equals(req.getRequestURI());
        AuthenticationFilter filter = new AuthenticationFilter(
                store, loader, new ObjectMapper(), new MarketingProperties(), java.util.List.of(customPolicy));

        MockHttpServletResponse openRes = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/custom-open"), openRes, (req, res) -> {});
        assertEquals(200, openRes.getStatus());

        // 未声明在 SPI 中的旧公开路径应被拒绝，证明过滤器不再硬编码上层路由
        MockHttpServletResponse blockedRes = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/s/abc"), blockedRes, (req, res) -> {});
        assertEquals(401, blockedRes.getStatus());
    }

    @Test
    void sanitizesUnsafeRequestIdAndRejectsStaleSessionVersion() throws Exception {
        AtomicReference<String> deletedToken = new AtomicReference<>();
        SessionStore store = new SessionStore() {
            @Override
            public void save(String token, UserSession session, Duration ttl) {
            }

            @Override
            public Optional<UserSession> find(String token) {
                UserSession session = new UserSession();
                session.setUserId(99L);
                session.setSessionVersion(1L);
                return Optional.of(session);
            }

            @Override
            public void delete(String token) {
                deletedToken.set(token);
            }
        };
        IdentityContextLoader loader = mock(IdentityContextLoader.class);
        when(loader.isRevoked(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        AuthenticationFilter filter = new AuthenticationFilter(store, loader, new ObjectMapper(), new MarketingProperties());

        // 含 CRLF/XSS/超长字符的非法 X-Request-Id 应被丢弃并重新生成安全 ID
        MockHttpServletRequest badHeaderReq = new MockHttpServletRequest("GET", "/api/health");
        badHeaderReq.addHeader("X-Request-Id", "bad\r\n<script>alert(1)</script>");
        MockHttpServletResponse cleanRes = new MockHttpServletResponse();
        filter.doFilter(badHeaderReq, cleanRes, (req, res) -> {});
        String returnedId = cleanRes.getHeader("X-Request-Id");
        org.junit.jupiter.api.Assertions.assertNotNull(returnedId);
        org.junit.jupiter.api.Assertions.assertTrue(returnedId.matches("^[A-Za-z0-9._-]{1,64}$"));
        org.junit.jupiter.api.Assertions.assertNotEquals("bad\r\n<script>alert(1)</script>", returnedId);

        // 会话版本号落后于用户当前版本号时，应立即清理令牌并返回 401
        MockHttpServletRequest staleReq = new MockHttpServletRequest("GET", "/api/user/profile");
        staleReq.addHeader("Authorization", "Bearer old-token");
        MockHttpServletResponse staleRes = new MockHttpServletResponse();
        filter.doFilter(staleReq, staleRes, (req, res) -> {});
        assertEquals(401, staleRes.getStatus());
        assertEquals("old-token", deletedToken.get());
    }
}
