package com.sayohno.marketing.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sayohno.marketing.support.MemoryStockConfiguration;
import com.sayohno.marketing.support.TestIdentityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestIdentityConfiguration.class, MemoryStockConfiguration.class})
class SimpleWorkspaceFlowTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;

    @Test
    void workspaceWithoutShopCanSaveDraftAllocateCouponAndGenerateAttributedEntry() throws Exception {
        String username = "simple_" + UUID.randomUUID().toString().substring(0, 8);
        JsonNode account = postData("/api/identity/register", null, Map.of("username", username, "password", "password123"));
        String token = account.path("token").asText();
        String workspace = account.path("merchantId").asText();
        assertFalse(workspace.isBlank());
        assertTrue(account.path("storeCode").isNull());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM iam_shop WHERE merchant_id=?", Integer.class, workspace));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM iam_merchant_member WHERE merchant_id=?", Integer.class, workspace));
        assertEquals(account.path("userId").asText(), db.queryForObject("SELECT owner_user_id FROM iam_merchant WHERE id=?", String.class, workspace));
        JsonNode login = postData("/api/identity/login", null, Map.of("username", username, "password", "password123"));
        assertEquals(workspace, login.path("merchantId").asText());
        token = login.path("token").asText();
        assertEquals(workspace, postData("/api/identity/switch-merchant", token, Map.of("merchantId", workspace)).path("merchantId").asText());

        LocalDateTime now = LocalDateTime.now().withNano(0);
        JsonNode draft = postData("/api/merchant/activities", token, Map.of("name", "简单活动", "activityType", "新客领取活动",
                "budgetAmount", "500.25", "startTime", now.plusHours(1).toString(), "endTime", now.plusDays(1).toString()));
        String id = draft.path("id").asText();
        JsonNode detail = getData("/api/merchant/activities/" + id, token);
        assertEquals("新客领取活动", detail.path("activityType").asText());
        assertEquals("500.25", detail.path("budgetAmount").asText());
        var patch = Map.of("name", "简单活动已编辑", "activityType", "会员专享活动", "budgetAmount", "600.50",
                "startTime", now.plusHours(1).toString(), "endTime", now.plusDays(1).toString(), "version", detail.path("version").asInt());
        mvc.perform(patch("/api/merchant/activities/" + id).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(patch))).andExpect(status().isOk());
        detail = getData("/api/merchant/activities/" + id, token);
        assertEquals("会员专享活动", detail.path("activityType").asText());
        assertEquals("600.5", detail.path("budgetAmount").decimalValue().stripTrailingZeros().toPlainString());

        JsonNode coupon = postData("/api/merchant/offer-plans", token, Map.of("title", "无店铺券", "issuerScope", "merchant", "applyScope", "storewide",
                "benefitKind", "amount_off", "inventoryQty", 20, "usableFrom", now.minusHours(1).toString(), "usableUntil", now.plusDays(3).toString(),
                "claimPolicy", "{\"maxClaimsPerUser\":1}", "benefitPolicy", "{\"capAmount\":\"10.00\",\"validityPeriodHours\":24}"));
        assertTrue(coupon.path("storeCode").isNull());
        mvc.perform(put("/api/merchant/activities/" + id + "/allocation").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("planId", coupon.path("id").asText(),
                        "quantity", 10, "maxClaimsPerUser", 1, "budgetAmount", "600.50", "version", detail.path("version").asInt(),
                        "idempotencyKey", UUID.randomUUID().toString())))).andExpect(status().isOk());
        detail = getData("/api/merchant/activities/" + id, token);
        assertEquals(coupon.path("id").asText(), detail.path("planId").asText());
        postData("/api/merchant/activities/" + id + "/main-link", token, Map.of());
        JsonNode channel = postData("/api/merchant/activities/" + id + "/channels", token,
                Map.of("name", "抖音", "channelCode", "dy", "idempotencyKey", UUID.randomUUID().toString()));
        assertEquals("dy", channel.path("channelCode").asText());
        assertTrue(getData("/api/merchant/activities/" + id, token).path("fullShortUrl").asText().contains("/s/"));

        JsonNode other = postData("/api/identity/register", null, Map.of("username", username + "_b", "password", "password123"));
        mvc.perform(get("/api/merchant/activities/" + id).header("Authorization", "Bearer " + other.path("token").asText()))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/merchant/activities/" + id).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("name", "预算过低", "budgetAmount", "1.00",
                        "startTime", now.plusHours(1).toString(), "endTime", now.plusDays(1).toString())))).andExpect(status().isBadRequest());
        assertEquals("600.5", getData("/api/merchant/activities/" + id, token).path("budgetAmount").decimalValue().stripTrailingZeros().toPlainString());
    }

    @Test
    void externalLinkGeneratesCodeWithoutGroupKeepsDestinationAndChecksActivityOwnership() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        JsonNode owner = postData("/api/identity/register", null, Map.of("username", "links_"+suffix, "password", "password123"));
        String token = owner.path("token").asText();
        LocalDateTime now = LocalDateTime.now();
        JsonNode activity = postData("/api/merchant/activities", token, Map.of("name", "短链归属", "startTime", now.plusDays(1).toString(), "endTime", now.plusDays(2).toString()));
        var request = Map.of("name", "推广页面", "originUrl", "https://example.com/promotion?from=campaign", "activityId", activity.path("id").asText(), "idempotencyKey", "simple-link");
        JsonNode link = postData("/api/merchant/links", token, request);
        String code = link.path("shortUri").asText();
        assertTrue(code.matches("[A-Za-z0-9]{6}"));
        assertEquals(activity.path("id").asText(), link.path("activityId").asText());
        assertEquals("https://example.com/promotion?from=campaign", link.path("originUrl").asText());
        assertEquals(link.path("id"), postData("/api/merchant/links", token, request).path("id"));
        JsonNode plain = postData("/api/merchant/links", token, Map.of("name", "独立链接", "originUrl", "https://example.com", "idempotencyKey", "plain"));
        assertTrue(plain.path("activityId").isNull());
        assertEquals(link.path("gid"), plain.path("gid"));
        assertEquals(2, getData("/api/merchant/links", token).size());
        mvc.perform(get("/s/"+code)).andExpect(status().isFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Location", "https://example.com/promotion?from=campaign"));
        JsonNode other = postData("/api/identity/register", null, Map.of("username", "other_"+suffix, "password", "password123"));
        mvc.perform(post("/api/merchant/links").header("Authorization", "Bearer "+other.path("token").asText())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request))).andExpect(status().isNotFound());
        var changed = Map.of("name", "推广页面", "originUrl", "https://example.com/promotion?from=campaign", "idempotencyKey", "simple-link");
        mvc.perform(post("/api/merchant/links").header("Authorization", "Bearer "+token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(changed))).andExpect(status().isConflict());
    }

    private JsonNode postData(String path, String token, Object body) throws Exception {
        var request = post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        String response = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        JsonNode root = json.readTree(response);
        assertEquals("0", root.path("code").asText());
        return root.path("data");
    }

    private JsonNode getData(String path, String token) throws Exception {
        return json.readTree(mvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).path("data");
    }
}
