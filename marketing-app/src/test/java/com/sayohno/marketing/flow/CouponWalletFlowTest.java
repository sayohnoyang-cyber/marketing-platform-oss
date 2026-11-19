package com.sayohno.marketing.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sayohno.marketing.common.context.CurrentUser;
import com.sayohno.marketing.common.context.UserContext;
import com.sayohno.marketing.common.context.UserRole;
import com.sayohno.marketing.coupon.enums.*;
import com.sayohno.marketing.coupon.dao.mapper.IssuedVoucherMapper;
import com.sayohno.marketing.coupon.service.VoucherClaimService;
import com.sayohno.marketing.coupon.service.OfferPlanService;
import com.sayohno.marketing.support.MemoryStockConfiguration;
import com.sayohno.marketing.support.TestIdentityConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestIdentityConfiguration.class, MemoryStockConfiguration.class})
class CouponWalletFlowTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired OfferPlanService templates;
    @Autowired VoucherClaimService claims;
    @Autowired IssuedVoucherMapper coupons;

    @AfterEach void clear() { UserContext.clear(); }

    @Test void walletAndManagementEnforceDifferentAuthorities() throws Exception {
        var merchant = register("MERCHANT");
        var other = register("MERCHANT");
        var user = register("USER");
        var stranger = register("USER");
        UserContext.set(CurrentUser.builder().userId(merchant.path("userId").asLong()).merchantId(merchant.path("merchantId").asLong())
                .roles(Set.of(UserRole.USER, UserRole.OWNER)).build());
        var now = LocalDateTime.now();
        var template = templates.create(null, "手机券包测试券", IssuerScope.MERCHANT, ApplyScope.STOREWIDE, null, BenefitKind.AMOUNT_OFF, 10, now.minusDays(1), now.plusDays(3),
                "{\"maxClaimsPerUser\":5}", "{\"minSpend\":\"10\",\"capAmount\":\"1.00\"}");
        var first = claims.claim(user.path("userId").asLong(), template.getId(), IssuerScope.MERCHANT, "wallet-first");
        var expired = claims.claim(user.path("userId").asLong(), template.getId(), IssuerScope.MERCHANT, "wallet-expired");
        expired.setUsableUntil(now.minusHours(1)); coupons.updateById(expired);
        UserContext.clear();
        String token = user.path("token").asText(), owner = merchant.path("token").asText();
        mvc.perform(get("/api/user/vouchers").header("Authorization", "Bearer " + token).param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].title").value("手机券包测试券"))
                .andExpect(jsonPath("$.data.records[0].status").value("expired"))
                .andExpect(jsonPath("$.data.records[0].usable").value(false))
                .andExpect(jsonPath("$.data.records[0].merchantId").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].planId").doesNotExist());
        mvc.perform(get("/api/user/vouchers").header("Authorization", "Bearer " + token).param("size", "1").param("page", "2"))
                .andExpect(jsonPath("$.data.records[0].id").value(first.getId().toString()))
                .andExpect(jsonPath("$.data.records[0].usable").value(true));
        mvc.perform(get("/api/user/vouchers").header("Authorization", "Bearer " + stranger.path("token").asText()))
                .andExpect(jsonPath("$.data.total").value(0));
        String path = "/api/user/vouchers/" + first.getId();
        mvc.perform(post(path + "/hold").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderNo\":\"wallet-order\",\"orderAmount\":100}"))
                .andExpect(status().isOk());
        String body = "{\"orderNo\":\"wallet-order\",\"refundAmount\":99}";
        for (String action : new String[]{"complete", "refund"}) {
            mvc.perform(post(path + "/" + action).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/merchant/vouchers/" + first.getId() + "/" + action)
                            .header("Authorization", "Bearer " + token).header("merchantId", merchant.path("merchantId").asText())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
        }
        String managed = "/api/merchant/vouchers/" + first.getId();
        mvc.perform(get(managed).header("Authorization", "Bearer " + other.path("token").asText())).andExpect(status().isNotFound());
        mvc.perform(post(managed + "/complete").header("Authorization", "Bearer " + other.path("token").asText())
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        mvc.perform(get(managed).header("Authorization", "Bearer " + owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.redemptions[0].orderNo").value("wallet-order"));
        mvc.perform(post(managed + "/complete").header("Authorization", "Bearer " + owner).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("completed"));
        mvc.perform(post(managed + "/refund").header("Authorization", "Bearer " + owner).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderNo\":\"wallet-order\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(managed + "/refund").header("Authorization", "Bearer " + owner).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.refundAmount").value(99));
        mvc.perform(get(path + "/redemptions").header("Authorization", "Bearer " + stranger.path("token").asText()))
                .andExpect(status().isNotFound());
    }

    @Test void merchantCanRedeemAvailableVoucherDirectlyWithIdempotencyAndIsolation() throws Exception {
        var merchant = register("MERCHANT");
        var other = register("MERCHANT");
        var user = register("USER");
        UserContext.set(CurrentUser.builder().userId(merchant.path("userId").asLong()).merchantId(merchant.path("merchantId").asLong())
                .roles(Set.of(UserRole.USER, UserRole.OWNER)).build());
        var now = LocalDateTime.now();
        var template = templates.create(null, "一步核销券", IssuerScope.MERCHANT, ApplyScope.STOREWIDE, null, BenefitKind.AMOUNT_OFF, 10, now.minusDays(1), now.plusDays(3),
                "{\"maxClaimsPerUser\":2}", "{\"minSpend\":\"10\",\"capAmount\":\"5.00\"}");
        var voucher = claims.claim(user.path("userId").asLong(), template.getId(), IssuerScope.MERCHANT, "direct-redeem-1");
        UserContext.clear();

        String owner = merchant.path("token").asText();
        String managed = "/api/merchant/vouchers/" + voucher.getId();
        String redeemBody = "{\"orderNo\":\"POS-1001\",\"orderAmount\":50}";

        // 跨工作空间拒绝
        mvc.perform(post(managed + "/redeem").header("Authorization", "Bearer " + other.path("token").asText())
                        .contentType(MediaType.APPLICATION_JSON).content(redeemBody))
                .andExpect(status().isNotFound());
        // 商家试算
        mvc.perform(post(managed + "/quote").header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON).content(redeemBody))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(5.0));
        // 未锁定可用券一步直接核销
        mvc.perform(post(managed + "/redeem").header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON).content(redeemBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("completed"))
                .andExpect(jsonPath("$.data.discountAmount").value(5.0));
        // 同订单号同金额幂等重试
        mvc.perform(post(managed + "/redeem").header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON).content(redeemBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("completed"));
        // 同订单号异金额返回冲突
        mvc.perform(post(managed + "/redeem").header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"orderNo\":\"POS-1001\",\"orderAmount\":80}"))
                .andExpect(status().isConflict());
    }

    private JsonNode register(String type) throws Exception {
        var body = Map.of("username", "w_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10), "password", "testPassword123", "registerType", type, "merchantName", "测试工作空间");
        var result = mvc.perform(post("/api/identity/register").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }
}
