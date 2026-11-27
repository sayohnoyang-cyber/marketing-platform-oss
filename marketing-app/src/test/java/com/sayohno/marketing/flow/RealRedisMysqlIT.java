package com.sayohno.marketing.flow;

import com.sayohno.marketing.common.context.CurrentUser;
import com.sayohno.marketing.common.context.UserContext;
import com.sayohno.marketing.common.context.UserRole;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.coupon.enums.*;
import com.sayohno.marketing.coupon.dao.entity.OfferPlan;
import com.sayohno.marketing.coupon.dao.mapper.OfferPlanMapper;
import com.sayohno.marketing.coupon.redis.OfferInventoryKeys;
import com.sayohno.marketing.coupon.service.VoucherClaimService;
import com.sayohno.marketing.coupon.service.OfferPlanService;
import com.sayohno.marketing.coupon.stock.OfferStockGateway;
import com.sayohno.marketing.coupon.stock.StockDecision;
import com.sayohno.marketing.identity.IamStatus;
import com.sayohno.marketing.identity.dao.entity.IamMerchant;
import com.sayohno.marketing.identity.dao.entity.IamShop;
import com.sayohno.marketing.identity.dao.mapper.IamMerchantMapper;
import com.sayohno.marketing.identity.dao.mapper.IamShopMapper;
import com.sayohno.marketing.shortlink.redis.ShortLinkRedisKeys;
import com.sayohno.marketing.shortlink.service.LinkDirectoryService;
import com.sayohno.marketing.shortlink.service.RedirectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "MARKETING_IT", matches = "true")
@SpringBootTest
@ActiveProfiles("it")
class RealRedisMysqlIT {
    @Autowired
    private OfferPlanService templateService;
    @Autowired
    private VoucherClaimService claimService;
    @Autowired
    private OfferPlanMapper templateMapper;
    @Autowired
    private OfferStockGateway stockGateway;
    @Autowired
    private IamMerchantMapper merchantMapper;
    @Autowired
    private IamShopMapper shopMapper;
    @Autowired
    private LinkDirectoryService linkDirectoryService;
    @Autowired
    private RedirectService redirectService;
    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private com.sayohno.marketing.common.lock.DistributedLock distributedLock;

    @Test
    void failingLockCallbackRunsOnceAndReleasesLock() {
        String name = "test:" + UUID.randomUUID();
        var ttl = java.time.Duration.ofSeconds(30);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        IllegalStateException failure = new IllegalStateException("任务失败");
        assertEquals(failure, assertThrows(IllegalStateException.class, () ->
                distributedLock.tryExecute(name, ttl, () -> {
                    calls.incrementAndGet();
                    throw failure;
                })));
        assertEquals(1, calls.get());
        assertTrue(distributedLock.tryExecute(name, ttl, calls::incrementAndGet));
        assertEquals(2, calls.get());
    }

    @AfterEach
    void clear() {
        UserContext.clear();
    }

    @Test
    void javaClientClaimsAgainstRedisAndMysqlAndCacheExpires() throws Exception {
        IamMerchant merchant = new IamMerchant();
        merchant.setName("集成" + UUID.randomUUID());
        merchant.setStatus(IamStatus.ENABLED);
        merchantMapper.insert(merchant);
        IamShop shop = new IamShop();
        shop.setMerchantId(merchant.getId());
        shop.setStoreCode("S" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        shop.setName("集成店");
        shop.setStatus(IamStatus.ENABLED);
        shopMapper.insert(shop);
        UserContext.set(CurrentUser.builder().userId(merchant.getId()).username("owner").merchantId(merchant.getId())
                .storeCode(shop.getStoreCode()).roles(Set.of(UserRole.OWNER, UserRole.USER)).build());
        OfferPlan template = templateService.create(shop.getStoreCode(), "集成券", IssuerScope.MERCHANT, ApplyScope.STOREWIDE, null, BenefitKind.AMOUNT_OFF, 2,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusHours(1),
                "{\"maxClaimsPerUser\":1}", "{\"capAmount\":\"1.00\",\"validityPeriodHours\":1}");
        assertNotNull(claimService.claim(11, template.getId(), IssuerScope.MERCHANT, "it-11"));
        assertEquals(claimService.claim(11, template.getId(), IssuerScope.MERCHANT, "it-11").getId(),
                claimService.claim(11, template.getId(), IssuerScope.MERCHANT, "it-11").getId());
        assertNotNull(claimService.claim(12, template.getId(), IssuerScope.MERCHANT, "it-12"));
        assertThrows(BizException.class, () -> claimService.claim(13, template.getId(), IssuerScope.MERCHANT, "it-13"));
        assertEquals(0, templateMapper.selectById(template.getId()).getInventoryQty());
        assertEquals("0", redis.opsForValue().get(OfferInventoryKeys.planInventory(template.getId())));

        long holdRequest = 900000000000L + (System.nanoTime() % 100000);
        StockDecision first = stockGateway.tryAcquire(template.getId() + 100, 21, 5, 2, 120, holdRequest);
        StockDecision later = stockGateway.tryAcquire(template.getId() + 100, 21, 5, 2, 120, holdRequest + 1);
        assertTrue(first.ok());
        assertTrue(later.ok());
        assertEquals(1, stockGateway.compensate(template.getId() + 100, 21, first.claimSeq(), holdRequest));
        assertEquals(0, stockGateway.compensate(template.getId() + 100, 21, first.claimSeq(), holdRequest));
        assertEquals("1", redis.opsForValue().get(OfferInventoryKeys.planInventory(template.getId() + 100)));

        var group = linkDirectoryService.createGroup("集成分组");
        var link = linkDirectoryService.createLink(group.getGid(), "https://example.com/fresh",
                LocalDateTime.now().plusSeconds(2), "it-link", null, null);
        assertEquals("https://example.com/fresh", redirectService.resolve(link.getShortUri(), "visitor-it").getOriginUrl());
        Long ttl = redis.getExpire(ShortLinkRedisKeys.gotoCache(link.getFullShortUrl()));
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= 3, "缓存 TTL 应不超过剩余有效期，实际 " + ttl);
        linkDirectoryService.updateLink(link.getId(), "https://example.com/changed", LocalDateTime.now().plusMinutes(10), 0);
        assertEquals("https://example.com/changed", redirectService.resolve(link.getShortUri(), "visitor-it").getOriginUrl());
        linkDirectoryService.recycle(link.getId());
        assertThrows(BizException.class, () -> redirectService.resolve(link.getShortUri(), "visitor-it"));
    }
}
