package com.sayohno.marketing.identity;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.sayohno.marketing.common.context.UserContext;

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
