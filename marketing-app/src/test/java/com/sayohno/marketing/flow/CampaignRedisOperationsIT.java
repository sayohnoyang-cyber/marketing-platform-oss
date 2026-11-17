package com.sayohno.marketing.flow;

import com.sayohno.marketing.campaign.service.*;
import com.sayohno.marketing.campaign.web.dto.*;
import com.sayohno.marketing.common.context.*;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.common.result.ErrorCode;
import com.sayohno.marketing.coupon.enums.*;
import com.sayohno.marketing.coupon.service.OfferPlanService;
import com.sayohno.marketing.identity.dao.entity.*;
import com.sayohno.marketing.identity.dao.mapper.*;
import com.sayohno.marketing.support.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real Lua execution in a separate Redis DB; business rows remain in H2. */
@EnabledIfEnvironmentVariable(named = "MARKETING_IT", matches = "true")
@SpringBootTest(properties = "marketing.capacity.policy-cache-ttl=0s")
@ActiveProfiles("test")
@Import({TestIdentityConfiguration.class, MemoryStockConfiguration.class, CampaignRedisOperationsIT.RedisConfig.class})
class CampaignRedisOperationsIT {
    @TestConfiguration static class RedisConfig {
        @Bean LettuceConnectionFactory operationsConnectionFactory() {
            var config = new RedisStandaloneConfiguration("127.0.0.1", Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "16387")));
            config.setDatabase(15);
            return new LettuceConnectionFactory(config);
        }
        @Bean StringRedisTemplate operationsRedis(LettuceConnectionFactory factory) { return new StringRedisTemplate(factory); }
    }
    @Autowired CampaignService campaigns;
    @Autowired CampaignFlowService flow;
    @Autowired OfferPlanService templates;
    @Autowired CapacityBaselineService capacity;
    @Autowired CampaignForecastService forecasts;
    @Autowired CampaignRateLimitService limits;
    @Autowired IamMerchantMapper merchants;
    @Autowired IamShopMapper shops;
    @AfterEach void clear() { UserContext.clear(); }

    @Test void savedStringQpsCanBeAppliedAndSwitchingSourceCannotBypassGlobalBudget() {
        var merchant = new IamMerchant(); merchant.setName("Redis限流验收"); merchant.setStatus(1); merchants.insert(merchant);
        var shop = new IamShop(); shop.setMerchantId(merchant.getId()); shop.setName("店"); shop.setStoreCode("S"+UUID.randomUUID().toString().substring(0,8)); shop.setStatus(1); shops.insert(shop);
        UserContext.set(CurrentUser.builder().userId(merchant.getId()).username("qa").merchantId(merchant.getId()).storeCode(shop.getStoreCode()).roles(Set.of(UserRole.OWNER,UserRole.USER)).build());
        var now = LocalDateTime.now();
        var template = templates.create(shop.getStoreCode(), "券", IssuerScope.MERCHANT, ApplyScope.STOREWIDE, null, BenefitKind.AMOUNT_OFF_OVER, 100, now.minusDays(1), now.plusDays(3), "{\"maxClaimsPerUser\":1}", "{\"capAmount\":\"1\",\"validityPeriodHours\":24}");
        var activity = campaigns.create("限流验收", now.minusHours(1), now.plusHours(1), now.plusDays(1));
        flow.saveOffer(activity.getId(),template.getId(),20,1,"offer",new BigDecimal("20"),null);
        flow.openMain(activity.getId()); var channel = flow.addChannel(activity.getId(),"社群","sq","sq"); campaigns.publish(activity.getId());
        var baseline = capacity.create(new CapacityRequest("local-single","仅测试用合成报告",1,10,20,BigDecimal.ZERO,1000,1000,now,now.plusDays(1)));
        var forecast = forecasts.save(activity.getId(),new ForecastRequest("save",Map.of("direct",50d,channel.getId().toString(),50d),0,2,"normal",100,Long.valueOf(baseline.get("id").toString())));
        var policy = limits.apply(activity.getId(),new ApplyLimitRequest("apply",Long.parseLong(forecast.get("id").toString())));
        assertEquals("ACTIVE",policy.get("status"));
        int blocked = 0, passed = 0;
        for (int i=0;i<5;i++) {
            try { limits.admit(activity.getId(), i%2 == 0 ? null : channel.getId()); passed++; }
            catch (BizException exception) { assertEquals(ErrorCode.RATE_LIMITED,exception.getErrorCode()); blocked++; }
        }
        assertTrue(passed > 0); assertTrue(blocked > 0);
        limits.disable(activity.getId());
        assertEquals("INACTIVE",limits.current(activity.getId()).get("status"));
        limits.admit(activity.getId(),null);
        // 策略因配置变更失效后回落到默认保护额度，不再拒绝全部领取。
        limits.apply(activity.getId(),new ApplyLimitRequest("apply-again",Long.parseLong(forecast.get("id").toString())));
        flow.setChannelEnabled(activity.getId(), channel.getId(), false);
        limits.admit(activity.getId(),null);
    }

    @Test
    void decreaseAllocationStockAtomicallyGuardsAgainstNegativeCache(@Autowired StringRedisTemplate operationsRedis) {
        var gateway = new com.sayohno.marketing.coupon.stock.RedisOfferStockGateway(operationsRedis);
        long inventoryId = 990001L + new Random().nextInt(10000);
        String key = com.sayohno.marketing.coupon.redis.OfferInventoryKeys.inventory(inventoryId);
        operationsRedis.delete(key);
        try {
            // 缓存未初始化时安全跳过
            gateway.decreaseAllocationIfPresent(inventoryId, 3);
            operationsRedis.opsForValue().set(key, "5");
            gateway.decreaseAllocationIfPresent(inventoryId, 3);
            assertEquals("2", operationsRedis.opsForValue().get(key));
            BizException ex = assertThrows(BizException.class, () -> gateway.decreaseAllocationIfPresent(inventoryId, 3));
            assertEquals(ErrorCode.CONFLICT, ex.getErrorCode());
            assertEquals("2", operationsRedis.opsForValue().get(key));
        } finally {
            operationsRedis.delete(key);
        }
    }
}
