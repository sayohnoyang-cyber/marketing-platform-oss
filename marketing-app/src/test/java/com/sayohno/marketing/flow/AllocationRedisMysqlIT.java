package com.sayohno.marketing.flow;

import com.sayohno.marketing.campaign.ActivityStatuses;
import com.sayohno.marketing.campaign.service.CampaignFlowService;
import com.sayohno.marketing.common.result.ErrorCode;
import com.sayohno.marketing.coupon.dao.mapper.OfferInventoryMapper;
import com.sayohno.marketing.coupon.redis.OfferInventoryKeys;
import com.sayohno.marketing.campaign.service.CampaignService;
import com.sayohno.marketing.common.context.CurrentUser;
import com.sayohno.marketing.common.context.UserContext;
import com.sayohno.marketing.common.context.UserRole;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.coupon.enums.*;
import com.sayohno.marketing.coupon.dao.entity.OfferPlan;
import com.sayohno.marketing.coupon.dao.mapper.OfferPlanMapper;
import com.sayohno.marketing.coupon.service.OfferInventoryService;
import com.sayohno.marketing.coupon.service.OfferPlanService;
import com.sayohno.marketing.coupon.stock.OfferStockGateway;
import com.sayohno.marketing.coupon.stock.StockDecision;
import com.sayohno.marketing.identity.IamStatus;
import com.sayohno.marketing.identity.dao.entity.IamMerchant;
import com.sayohno.marketing.identity.dao.entity.IamShop;
import com.sayohno.marketing.identity.dao.mapper.IamMerchantMapper;
import com.sayohno.marketing.identity.dao.mapper.IamShopMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "MARKETING_IT", matches = "true")
@SpringBootTest
@ActiveProfiles("it")
class AllocationRedisMysqlIT {
    @Autowired
    private CampaignService campaignService;
    @Autowired
    private CampaignFlowService flowService;
    @Autowired
    private OfferPlanService templateService;
    @Autowired
    private OfferInventoryService allocationService;
    @Autowired
    private OfferPlanMapper templateMapper;
    @Autowired
    private OfferStockGateway stockGateway;
    @Autowired
    private IamMerchantMapper merchantMapper;
    @Autowired
    private IamShopMapper shopMapper;

    @AfterEach
    void clear() {
        UserContext.clear();
    }

    @Test
    void allocationStaysWithinMysqlAndRedis() throws Exception {
        Shop shop = shop();
        asMerchant(shop);
        LocalDateTime now = LocalDateTime.now();
        OfferPlan template = templateService.create(shop.storeCode(), "真实投放", IssuerScope.MERCHANT, ApplyScope.STOREWIDE, null, BenefitKind.AMOUNT_OFF, 10,
                now.minusDays(1), now.plusDays(3),
                "{\"maxClaimsPerUser\":2}", "{\"capAmount\":\"1.00\",\"validityPeriodHours\":24}");
        var first = campaignService.create("真实一场", now.plusHours(1), now.plusHours(2), now.plusDays(2));
        var second = campaignService.create("真实二场", now.plusHours(1), now.plusHours(2), now.plusDays(2));
        campaignService.attachCoupon(first.getId(), template.getId());
        campaignService.attachCoupon(second.getId(), template.getId());
        flowService.place(first.getId(), 6, 1, "mysql-a", new BigDecimal("100.00"));
        assertThrows(BizException.class, () -> flowService.place(second.getId(), 6, 1, "mysql-b", new BigDecimal("100.00")));
        flowService.place(second.getId(), 4, 1, "mysql-c", new BigDecimal("100.00"));
        OfferPlan fresh = templateMapper.selectById(template.getId());
        assertEquals(10, fresh.getIssuedTotal());
        assertEquals(0, fresh.getInventoryQty());
        assertEquals(10, allocationService.allocatedQuantity(template.getId()));

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        OfferPlan raced = templateService.create(shop.storeCode(), "真实并发", IssuerScope.MERCHANT, ApplyScope.STOREWIDE, null, BenefitKind.AMOUNT_OFF, 10,
                now.minusDays(1), now.plusDays(3),
                "{\"maxClaimsPerUser\":1}", "{\"capAmount\":\"1.00\",\"validityPeriodHours\":24}");
        var left = campaignService.create("并发甲", now.plusHours(1), now.plusHours(3), now.plusDays(2));
        var right = campaignService.create("并发乙", now.plusHours(1), now.plusHours(3), now.plusDays(2));
        campaignService.attachCoupon(left.getId(), raced.getId());
        campaignService.attachCoupon(right.getId(), raced.getId());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> one = pool.submit(() -> place(shop, left.getId(), "race-mysql-a", latch, wins));
        Future<?> two = pool.submit(() -> place(shop, right.getId(), "race-mysql-b", latch, wins));
        latch.countDown();
        one.get();
        two.get();
        pool.shutdown();
        assertEquals(1, wins.get());
        assertTrue(templateMapper.selectById(raced.getId()).getInventoryQty() >= 4);

        flowService.addChannel(first.getId(), "微信", "mysql-wx");
        flowService.addChannel(second.getId(), "短信", "mysql-sms");
        campaignService.publish(first.getId());
        campaignService.publish(second.getId());
        campaignService.postpone(first.getId(), now.minusHours(2), now.minusMinutes(1), now.plusDays(1));
        campaignService.postpone(second.getId(), now.minusHours(2), now.minusMinutes(1), now.plusDays(1));
        var link = flowService.channels(first.getId()).get(0);
        var entry = flowService.entry(requireShort(link.getLinkId()), link.getChannelCode(), "visitor");
        var claimed = flowService.claim(31, first.getId(), template.getId(), "same-real", (String) entry.get("credential"));
        var repeated = flowService.claim(31, first.getId(), template.getId(), "same-real", (String) entry.get("credential"));
        assertEquals(claimed.getId(), repeated.getId());
        var otherEntry = flowService.entry(requireShort(flowService.channels(second.getId()).get(0).getLinkId()),
                flowService.channels(second.getId()).get(0).getChannelCode(), "visitor-2");
        var other = flowService.claim(31, second.getId(), template.getId(), "same-real", (String) otherEntry.get("credential"));
        assertNotEquals(claimed.getId(), other.getId());

        long inventoryId = campaignService.coupons(first.getId()).get(0).getInventoryId();
        StockDecision decision = stockGateway.tryAcquireAllocation(inventoryId, template.getId(), 77, 1, 2, 1, 120, 88001);
        if (decision.ok()) {
            assertEquals(1, stockGateway.compensateAllocation(inventoryId, template.getId(), 77, decision.claimSeq(), decision.allocCount(), 88001));
            assertEquals(0, stockGateway.compensateAllocation(inventoryId, template.getId(), 77, decision.claimSeq(), decision.allocCount(), 88001));
        }
        assertThrows(BizException.class, () -> campaignService.publish(first.getId()));
        Shop stranger = shop();
        asMerchant(stranger);
        assertThrows(BizException.class, () -> campaignService.requireOwned(first.getId()));
    }

    @Autowired
    private com.sayohno.marketing.shortlink.dao.mapper.LinkMapper linkMapper;

    private String requireShort(long linkId) {
        return linkMapper.selectById(linkId).getShortUri();
    }

    private void place(Shop shop, long activityId, String key, CountDownLatch latch, AtomicInteger wins) {
        try {
            latch.await();
            asMerchant(shop);
            flowService.place(activityId, 6, 1, key, new BigDecimal("100.00"));
            wins.incrementAndGet();
        } catch (BizException ignored) {
            return;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            UserContext.clear();
        }
    }

    private OfferPlan createPlan(Shop shop, int stock) {
        asMerchant(shop);
        LocalDateTime now = LocalDateTime.now();
        return templateService.create(shop.storeCode(), "库存回归券", IssuerScope.MERCHANT,
                ApplyScope.STOREWIDE, null, BenefitKind.AMOUNT_OFF, stock, now.minusDays(1), now.plusDays(3),
                "{\"maxClaimsPerUser\":1}", "{\"capAmount\":1}");
    }

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactions;

    @Test
    void renamingPlanPreservesConcurrentStockIncrease() {
        Shop shop = shop();
        OfferPlan plan = createPlan(shop, 3);
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            templateMapper.selectById(plan.getId());
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    asMerchant(shop);
                    templateService.increaseStock(plan.getId(), 5);
                } finally {
                    UserContext.clear();
                }
            }).join();
            templateService.revise(plan.getId(), "新的券名", null, null, null, null);
        });
        OfferPlan edited = templateMapper.selectById(plan.getId());
        assertEquals("新的券名", edited.getTitle());
        assertEquals(8, edited.getInventoryQty());
        assertEquals(8, edited.getIssuedTotal());
    }

    @Test
    void liveAllocationUsesEffectiveBudgetAndRollsBackRejectedChanges() {
        OfferPlan plan = createPlan(shop(), 5);
        LocalDateTime now = LocalDateTime.now();
        var activity = campaignService.create("活动预算", now.plusHours(1), now.plusHours(2), now.plusDays(2));
        long id = activity.getId();
        campaignService.attachCoupon(id, plan.getId());
        var offer = flowService.place(id, 1, 1, "initial", BigDecimal.ONE);
        campaignService.publish(id);
        campaignService.postpone(id, now.minusHours(2), now.minusMinutes(1), now.plusDays(1));

        assertThrows(BizException.class, () -> flowService.place(id, 3, 1, "append", null));
        assertThrows(BizException.class, () -> flowService.place(id, 3, 1, "append", BigDecimal.valueOf(2)));
        assertEquals(1, allocationService.find(offer.getInventoryId()).getQuantity());
        assertEquals(0, BigDecimal.ONE.compareTo(campaignService.requireOwned(id).getBudgetAmount()));
        flowService.place(id, 3, 1, "append", BigDecimal.valueOf(3));
        assertEquals(3, allocationService.find(offer.getInventoryId()).getQuantity());
    }

    @Test
    void equalPlacementKeysInDifferentActivitiesCreateIndependentInventories() {
        OfferPlan plan = createPlan(shop(), 4);
        LocalDateTime now = LocalDateTime.now();
        var first = campaignService.create("第一场", now.plusHours(1), now.plusHours(2), now.plusDays(1));
        var second = campaignService.create("第二场", now.plusHours(1), now.plusHours(2), now.plusDays(1));
        campaignService.attachCoupon(first.getId(), plan.getId());
        campaignService.attachCoupon(second.getId(), plan.getId());
        var a = flowService.place(first.getId(), 2, 1, "same", BigDecimal.TEN);
        var b = flowService.place(second.getId(), 2, 1, "same", BigDecimal.TEN);
        assertNotEquals(a.getInventoryId(), b.getInventoryId());
        assertEquals(b.getInventoryId(), flowService.place(second.getId(), 2, 1, "same", BigDecimal.TEN).getInventoryId());
        assertEquals(0, templateMapper.selectById(plan.getId()).getInventoryQty());
        allocationService.freeze(a.getInventoryId());
        assertEquals(InventoryStatus.OPEN, allocationService.find(b.getInventoryId()).getStatus());
    }

    @Autowired
    private com.sayohno.marketing.coupon.service.VoucherClaimService claims;

    @Test
    void returnedAllocationStockIsAvailableToDirectClaims() {
        OfferPlan plan = createPlan(shop(), 4);
        var allocation = allocationService.allocate(plan.getId(), 3, 1, "place");
        claims.claim(101, plan.getId(), IssuerScope.MERCHANT, "initial");
        allocationService.adjust(allocation.getId(), 2, 1, "reduce", true, true);
        claims.claim(102, plan.getId(), IssuerScope.MERCHANT, "after-reduction");
        allocationService.freeze(allocation.getId());
        allocationService.freeze(allocation.getId());
        claims.claim(103, plan.getId(), IssuerScope.MERCHANT, "after-freeze");
        claims.claim(104, plan.getId(), IssuerScope.MERCHANT, "last");
        assertEquals(0, templateMapper.selectById(plan.getId()).getInventoryQty());
        assertThrows(BizException.class, () -> claims.claim(105, plan.getId(), IssuerScope.MERCHANT, "sold-out"));
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private OfferInventoryMapper inventoryMapper;

    @Test
    void deleteWaitsForConcurrentPublishAndPreservesAllocation() throws Exception {
        Shop shop = shop();
        OfferPlan plan = createPlan(shop, 5);
        LocalDateTime now = LocalDateTime.now();
        long id = campaignService.create("并发发布删除", now.plusHours(1), now.plusHours(2), now.plusDays(2)).getId();
        campaignService.attachCoupon(id, plan.getId());
        long inventory = flowService.place(id, 3, 1, "draft", BigDecimal.TEN).getInventoryId();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<BizException> deletion = new TransactionTemplate(transactions).execute(tx -> {
                campaignService.publish(id);
                // 暂不提交发布，让删除真正进入 InnoDB 的锁等待，再释放发布事务。
                var result = pool.submit(() -> {
                    asMerchant(shop);
                    try {
                        return assertThrows(BizException.class, () -> flowService.deleteDraft(id));
                    } finally {
                        UserContext.clear();
                    }
                });
                awaitLockWaiters(1);
                return result;
            });
            assertEquals(ErrorCode.CONFLICT, deletion.get(10, TimeUnit.SECONDS).getErrorCode());
            assertEquals(ActivityStatuses.PUBLISHED, campaignService.requireOwned(id).getStatus());
            assertEquals(inventory, campaignService.coupons(id).get(0).getInventoryId());
            assertEquals(InventoryStatus.OPEN, allocationService.find(inventory).getStatus());
            assertEquals(2, templateMapper.selectById(plan.getId()).getInventoryQty());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void missingRedisQuotaCannotExceedPlanOrAllocationLimit() throws Exception {
        Shop shop = shop();
        asMerchant(shop);
        // 分开验证方案总限额和较低的单场限额，以免一个判断掩盖另一个判断。
        verifyLostQuota(shop, 2, 2, true);
        verifyLostQuota(shop, 3, 2, false);
    }

    private void verifyLostQuota(Shop shop, int planLimit, int poolLimit, boolean firstClaimIsDirect) throws Exception {
        long userId = 701L;
        LocalDateTime now = LocalDateTime.now();
        long plan = templateService.create(shop.storeCode(), "限领恢复", IssuerScope.MERCHANT, ApplyScope.STOREWIDE,
                null, BenefitKind.AMOUNT_OFF, 10, now.minusDays(1), now.plusDays(2),
                "{\"maxClaimsPerUser\":" + planLimit + "}", "{\"capAmount\":1}").getId();
        long inventory = allocationService.allocate(plan, 6, poolLimit, "pool-" + plan).getId();
        if (firstClaimIsDirect) {
            claims.claim(userId, plan, IssuerScope.MERCHANT, "first");
        } else {
            claims.claimAllocation(userId, plan, inventory, IssuerScope.MERCHANT, "first");
        }
        redis.delete(List.of(OfferInventoryKeys.planQuota(userId, plan),
                OfferInventoryKeys.inventoryQuota(plan, userId, inventory)));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = new TransactionTemplate(transactions).execute(tx -> {
                inventoryMapper.lockById(inventory);
                var pending = List.of("second", "third").stream().map(key -> pool.submit(() -> {
                    try {
                        claims.claimAllocation(userId, plan, inventory, IssuerScope.MERCHANT, key);
                        return true;
                    } catch (BizException denied) {
                        assertEquals(ErrorCode.VALIDATION, denied.getErrorCode());
                        assertEquals(firstClaimIsDirect ? "已达到领取上限" : "已达到本场领取上限", denied.getMessage());
                        return false;
                    }
                })).toList();
                // 两笔请求已独立通过 Redis 预扣，均在等待数据库投放锁。
                awaitLockWaiters(2);
                return pending;
            });
            int accepted = 0;
            for (var result : results) {
                if (result.get(10, TimeUnit.SECONDS)) accepted++;
            }
            assertEquals(1, accepted);
            int issuedFromPool = firstClaimIsDirect ? 1 : 2;
            assertEquals(issuedFromPool, claims.receivedCount(userId, inventory));
            assertEquals(issuedFromPool, allocationService.find(inventory).getClaimedCount());
            assertEquals(6 - issuedFromPool, stockGateway.readAllocationRemaining(plan, inventory));
        } finally {
            pool.shutdownNow();
        }
    }

    private void awaitLockWaiters(int expected) {
        org.awaitility.Awaitility.await().pollInSameThread().atMost(Duration.ofSeconds(10)).until(() ->
                jdbc.queryForObject("""
                        SELECT COUNT(DISTINCT requesting_thread_id) FROM performance_schema.data_lock_waits
                        WHERE blocking_thread_id = PS_CURRENT_THREAD_ID()
                        """, Integer.class) >= expected);
    }

    private Shop shop() {
        IamMerchant merchant = new IamMerchant();
        merchant.setName("真实商家" + UUID.randomUUID());
        merchant.setStatus(IamStatus.ENABLED);
        merchantMapper.insert(merchant);
        IamShop row = new IamShop();
        row.setMerchantId(merchant.getId());
        row.setStoreCode("S" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        row.setName("店");
        row.setStatus(IamStatus.ENABLED);
        shopMapper.insert(row);
        return new Shop(merchant.getId(), row.getStoreCode());
    }

    private void asMerchant(Shop shop) {
        UserContext.set(CurrentUser.builder().userId(shop.merchantId()).username("owner").merchantId(shop.merchantId())
                .storeCode(shop.storeCode()).roles(Set.of(UserRole.OWNER, UserRole.USER)).build());
    }

    private record Shop(long merchantId, String storeCode) {
    }
}
