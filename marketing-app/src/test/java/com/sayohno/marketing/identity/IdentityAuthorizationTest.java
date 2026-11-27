package com.sayohno.marketing.identity;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sayohno.marketing.common.config.MarketingProperties;
import com.sayohno.marketing.common.context.CurrentUser;
import com.sayohno.marketing.common.context.UserContext;
import com.sayohno.marketing.identity.dao.entity.IamMerchant;
import com.sayohno.marketing.identity.dao.mapper.IamMerchantMapper;
import com.sayohno.marketing.identity.service.AuthService;
import com.sayohno.marketing.identity.web.dto.ChangePasswordRequest;
import com.sayohno.marketing.identity.dao.entity.IamUser;
import com.sayohno.marketing.identity.dao.mapper.IamUserMapper;
import com.sayohno.marketing.identity.session.SessionStore;
import com.sayohno.marketing.support.TestIdentityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestIdentityConfiguration.class)
class IdentityAuthorizationTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private IamUserMapper userMapper;
    @Autowired
    private SessionStore sessionStore;

    @Autowired private IamMerchantMapper merchantMapper;
    @Autowired private MarketingProperties properties;
    @Autowired private AuthService authService;
    @Autowired private TransactionTemplate transactions;

    @Test
    void disabledWorkspaceRejectsCachedMemberSwitchAndDefaultLogin() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = "op_" + suffix;
        Login owner = register("ws_" + suffix, "WORKSPACE", null);
        register(username, "USER", null);
        mockMvc.perform(post("/api/merchant/members").header("Token", owner.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "role", "OPERATOR"))))
                .andExpect(status().isOk());

        Duration previous = properties.getSession().getContextCacheTtl();
        try {
            properties.getSession().setContextCacheTtl(Duration.ofMinutes(1));
            Login member = login(username);
            assertEquals(owner.merchantId(), member.merchantId());
            mockMvc.perform(get("/api/merchant/shops").header("Token", member.token())).andExpect(status().isOk());
            merchantMapper.update(null, Wrappers.<IamMerchant>lambdaUpdate()
                    .eq(IamMerchant::getId, owner.merchantId()).set(IamMerchant::getStatus, IamStatus.DISABLED));

            // 不发送缓存失效通知，验证已登录成员、主动切换和新登录三个入口。
            mockMvc.perform(get("/api/merchant/shops").header("Token", member.token())).andExpect(status().isForbidden());
            mockMvc.perform(post("/api/identity/switch-merchant").header("Token", member.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("merchantId", owner.merchantId()))))
                    .andExpect(status().isForbidden());
            assertNull(login(username).merchantId());
        } finally {
            properties.getSession().setContextCacheTtl(previous);
        }
    }

    @Test
    void passwordRevocationCommitsWithHashEvenWhenOldSessionDataSurvives() throws Exception {
        String username = "rev_" + UUID.randomUUID().toString().substring(0, 8);
        Login account = register(username, "WORKSPACE", null);
        Login otherDevice = login(username);
        long userId = Long.parseLong(account.userId());
        long originalVersion = userMapper.sessionVersion(userId);
        ChangePasswordRequest change = new ChangePasswordRequest();
        change.setOldPassword("password123");
        change.setNewPassword("newPassword123");

        // 在提交前就能看到同一事务内的版本更新，回滚必须同时恢复密码与会话资格。
        UserContext.set(CurrentUser.builder().userId(userId).build());
        try {
            transactions.executeWithoutResult(tx -> {
                authService.changePassword(change);
                assertEquals(originalVersion + 1, userMapper.sessionVersion(userId));
                tx.setRollbackOnly();
            });
        } finally {
            UserContext.clear();
        }
        assertEquals(originalVersion, userMapper.sessionVersion(userId));
        mockMvc.perform(get("/api/user/profile").header("Token", otherDevice.token())).andExpect(status().isOk());
        mockMvc.perform(post("/api/identity/password").header("Token", account.token())
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(change)))
                .andExpect(status().isOk());
        assertEquals(originalVersion + 1, userMapper.sessionVersion(userId));
        for (Login old : List.of(account, otherDevice)) {
            assertEquals(originalVersion, sessionStore.find(old.token()).orElseThrow().getSessionVersion());
            mockMvc.perform(get("/api/user/profile").header("Token", old.token())).andExpect(status().isUnauthorized());
        }
        var fresh = readLogin(mockMvc.perform(post("/api/identity/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", change.getNewPassword()))))
                .andExpect(status().isOk()).andReturn());
        mockMvc.perform(get("/api/user/profile").header("Token", fresh.token())).andExpect(status().isOk());
    }

    @Test
    void healthDoesNotRequireLogin() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.status").value("UP"));
        assertNull(UserContext.get());
    }

    @Test
    void merchantsAreIsolatedAndHeadersCannotImpersonate() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Login a = register("m_a_" + suffix, "MERCHANT", "商家A");
        Login b = register("m_b_" + suffix, "MERCHANT", "商家B");
        Login visitor = register("u_" + suffix, "USER", null);

        IamUser stored = userMapper.selectOne(Wrappers.<IamUser>lambdaQuery().eq(IamUser::getUsername, "m_a_" + suffix));
        assertTrue(stored.getPasswordHash().startsWith("$2"));
        assertNotEquals("password123", stored.getPasswordHash());

        mockMvc.perform(get("/api/merchant/shops")
                        .header("Authorization", "Bearer " + a.token())
                        .header("merchantId", b.merchantId())
                        .header("storeCode", "SFOREIGN")
                        .header("gid", "stolen")
                        .param("merchantId", b.merchantId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data[0].merchantId").value(a.merchantId()))
                .andExpect(jsonPath("$.data[0].storeCode").value(a.storeCode()));

        mockMvc.perform(get("/api/merchant/shops/" + a.storeCode())
                        .header("Authorization", "Bearer " + b.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A000002"));

        mockMvc.perform(get("/api/merchant/shops")
                        .header("Authorization", "Bearer " + visitor.token()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A000002"));

        mockMvc.perform(get("/api/merchant/shops"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A000001"));

        mockMvc.perform(get("/api/user/profile")
                        .header("Authorization", "Bearer " + a.token())
                        .header("username", "m_b_" + suffix)
                        .header("userId", b.userId())
                        .header("merchantId", b.merchantId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("m_a_" + suffix))
                .andExpect(jsonPath("$.data.userId").value(a.userId()));

        mockMvc.perform(post("/api/merchant/members")
                        .header("Authorization", "Bearer " + a.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "u_" + suffix, "role", "OPERATOR"))))
                .andExpect(status().isOk());
        Login operator = login("u_" + suffix);
        mockMvc.perform(post("/api/merchant/shops")
                        .header("Authorization", "Bearer " + operator.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"越权店铺\"}"))
                .andExpect(status().isForbidden());

        stored.setStatus(0);
        userMapper.updateById(stored);
        mockMvc.perform(get("/api/user/profile").header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isUnauthorized());

        sessionStore.delete(b.token());
        mockMvc.perform(get("/api/user/profile").header("Authorization", "Bearer " + b.token()))
                .andExpect(status().isUnauthorized());
        assertNull(UserContext.get());
    }

    @Test
    void userCanChangePasswordWithCurrentPasswordVerification() throws Exception {
        String username = "pwd_" + UUID.randomUUID().toString().substring(0, 8);
        Login account = register(username, "WORKSPACE", null);

        mockMvc.perform(post("/api/identity/password")
                        .header("Authorization", "Bearer " + account.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"wrongPass1\",\"newPassword\":\"newPassword123\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/identity/password")
                        .header("Authorization", "Bearer " + account.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"password123\",\"newPassword\":\"password123\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/identity/password")
                        .header("Authorization", "Bearer " + account.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"password123\",\"newPassword\":\"newPassword123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"));

        // 改密提交后，此前持有的旧令牌应因会话版本递增而立即被吊销
        mockMvc.perform(get("/api/user/profile")
                        .header("Authorization", "Bearer " + account.token()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A000001"));

        mockMvc.perform(post("/api/identity/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/identity/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"newPassword123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty());
    }

    @Test
    void corsHeadersAreAttachedOnUnauthorizedResponseAndLoginLocksOutAfterTenFailures() throws Exception {
        mockMvc.perform(get("/api/merchant/shops")
                        .header("Origin", "http://localhost:5173"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().string("Access-Control-Expose-Headers", org.hamcrest.Matchers.containsString("X-Request-Id")));

        String username = "lock_" + UUID.randomUUID().toString().substring(0, 8);
        register(username, "USER", null);

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/identity/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"wrong_" + i + "\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("A000006"));
        }

        // 连续 10 次失败后，第 11 次即便传入正确密码也应被限流拦截（429 RATE_LIMITED）
        mockMvc.perform(post("/api/identity/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("A000008"));
    }

    private Login register(String username, String type, String merchantName) throws Exception {
        String body = merchantName == null
                ? "{\"username\":\"" + username + "\",\"password\":\"password123\",\"registerType\":\"" + type + "\"}"
                : "{\"username\":\"" + username + "\",\"password\":\"password123\",\"registerType\":\"" + type + "\",\"merchantName\":\"" + merchantName + "\"}";
        MvcResult result = mockMvc.perform(post("/api/identity/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andReturn();
        return readLogin(result);
    }

    private Login login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/identity/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return readLogin(result);
    }

    private Login readLogin(MvcResult result) throws Exception {
        var data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        return new Login(data.path("token").asText(), data.path("userId").asText(),
                data.path("merchantId").asText(null), data.path("storeCode").asText(null));
    }

    private record Login(String token, String userId, String merchantId, String storeCode) {
    }
}
