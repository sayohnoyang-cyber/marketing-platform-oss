package com.sayohno.marketing.flow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sayohno.marketing.common.context.CurrentUser;
import com.sayohno.marketing.common.context.UserContext;
import com.sayohno.marketing.common.context.UserRole;
import com.sayohno.marketing.common.outbox.OutboxEvent;
import com.sayohno.marketing.coupon.enums.*;
import com.sayohno.marketing.coupon.dao.entity.GrantBatch;
import com.sayohno.marketing.coupon.dao.entity.OfferPlan;
import com.sayohno.marketing.coupon.dao.entity.IssuedVoucher;
import com.sayohno.marketing.coupon.dao.mapper.GrantBatchMapper;
import com.sayohno.marketing.coupon.dao.mapper.IssuedVoucherMapper;
import com.sayohno.marketing.coupon.service.VoucherClaimService;
import com.sayohno.marketing.coupon.service.GrantBatchService;
import com.sayohno.marketing.coupon.service.OfferPlanService;
import com.sayohno.marketing.identity.IamStatus;
import com.sayohno.marketing.identity.dao.entity.IamMerchant;
import com.sayohno.marketing.identity.dao.entity.IamShop;
import com.sayohno.marketing.identity.dao.mapper.IamMerchantMapper;
import com.sayohno.marketing.identity.dao.mapper.IamShopMapper;
import com.sayohno.marketing.shortlink.dao.entity.Link;
import com.sayohno.marketing.shortlink.dao.entity.LinkStatsDaily;
import com.sayohno.marketing.shortlink.dao.entity.LinkStatsVisitor;
import com.sayohno.marketing.shortlink.dao.mapper.LinkMapper;
import com.sayohno.marketing.shortlink.dao.mapper.LinkStatsDailyMapper;
import com.sayohno.marketing.shortlink.dao.mapper.LinkStatsVisitorMapper;
import com.sayohno.marketing.shortlink.mq.StatsApplyProbe;
import com.sayohno.marketing.shortlink.mq.VisitStatsHandler;
import com.sayohno.marketing.shortlink.service.LinkDirectoryService;
import com.sayohno.marketing.shortlink.service.RedirectService;
import com.sayohno.marketing.support.MemoryStockConfiguration;
import com.sayohno.marketing.support.TestIdentityConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestIdentityConfiguration.class, MemoryStockConfiguration.class, RepairChecksTest.FailOnce.class})
class RepairChecksTest {
    @Autowired
    private OfferPlanService templateService;
    @Autowired
    private VoucherClaimService claimService;
    @Autowired
    private IssuedVoucherMapper userMapper;
    @Autowired
    private GrantBatchService taskService;
    @Autowired
    private GrantBatchMapper taskMapper;
    @Autowired
    private IamMerchantMapper merchantMapper;
    @Autowired
    private IamShopMapper shopMapper;
    @Autowired
    private LinkDirectoryService linkDirectoryService;
    @Autowired
    private LinkMapper linkMapper;
    @Autowired
    private VisitStatsHandler visitStatsHandler;
    @Autowired
    private LinkStatsDailyMapper statsMapper;
    @Autowired
    private LinkStatsVisitorMapper visitorMapper;

    @AfterEach
    void clear() {
        UserContext.clear();
    }

    @Test
    void sameRequestCannotClaimTwiceAndExpiredTemplateStillReturnsTheCoupon() throws Exception {
        Shop shop = shop();
        asMerchant(shop);
        OfferPlan template = templateService.create(shop.storeCode(), "幂等", IssuerScope.MERCHANT, ApplyScope.STOREWIDE, null, BenefitKind.AMOUNT_OFF, 5,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusDays(1),
                "{\"maxClaimsPerUser\":2}", "{\"capAmount\":\"1.00\",\"validityPeriodHours\":1}");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<IssuedVoucher> first = new AtomicReference<>();
        AtomicReference<IssuedVoucher> second = new AtomicReference<>();
        AtomicInteger failures = new AtomicInteger();
        pool.submit(call(start, null, () -> first.set(claimService.claim(8, template.getId(), IssuerScope.MERCHANT, "same-request")), failures));
        pool.submit(call(start, null, () -> second.set(claimService.claim(8, template.getId(), IssuerScope.MERCHANT, "same-request")), failures));
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(1L, userMapper.selectCount(Wrappers.<IssuedVoucher>lambdaQuery()
                .eq(IssuedVoucher::getUserId, 8L).eq(IssuedVoucher::getPlanId, template.getId())));
        IssuedVoucher again = claimService.claim(8, template.getId(), IssuerScope.MERCHANT, "same-request");
        assertEquals(again.getId(), userMapper.selectList(Wrappers.<IssuedVoucher>lambdaQuery()
                .eq(IssuedVoucher::getUserId, 8L).eq(IssuedVoucher::getPlanId, template.getId())).get(0).getId());
        templateService.terminate(template.getId());
        assertEquals(again.getId(), claimService.claim(8, template.getId(), IssuerScope.MERCHANT, "same-request").getId());
    }

    @Test
    void lostTaskOwnerCannotOverwriteTheResult() {
        Shop shop = shop();
        asMerchant(shop);
        GrantBatch task = new GrantBatch();
        task.setMerchantId(shop.merchantId());
        task.setPlanId(1L);
        task.setBatchCode(UUID.randomUUID().toString().replace("-", ""));
        task.setFilePath("/tmp/none.xlsx");
        task.setRunAt(LocalDateTime.now().minusSeconds(1));
        task.setStatus(GrantBatchStatus.QUEUED);
        task.setProgress(0);
        task.setTotalCount(1);
        task.setSuccessCount(0);
        task.setFailCount(0);
        taskMapper.insert(task);
        GrantBatch future = new GrantBatch();
        future.setMerchantId(shop.merchantId());
        future.setPlanId(1L);
        future.setBatchCode("later-" + task.getBatchCode());
        future.setFilePath("/tmp/later.xlsx");
        future.setRunAt(LocalDateTime.now().plusHours(3));
        future.setStatus(GrantBatchStatus.QUEUED);
        future.setProgress(0);
        future.setTotalCount(0);
        future.setSuccessCount(0);
        future.setFailCount(0);
        taskMapper.insert(future);
        assertEquals(GrantBatchStatus.QUEUED, taskMapper.selectById(future.getId()).getStatus());
        LocalDateTime now = LocalDateTime.now();
        assertEquals(1, taskMapper.acquire(task.getId(), "owner-a", now.plusMinutes(5), now));
        assertEquals(0, taskMapper.acquire(task.getId(), "owner-b", now.plusMinutes(5), now));
        assertEquals(0, taskMapper.finishOwned(task.getId(), "owner-b", GrantBatchStatus.FAILED, now));
        taskMapper.update(null, Wrappers.<GrantBatch>lambdaUpdate()
                .eq(GrantBatch::getId, task.getId())
                .set(GrantBatch::getLeaseUntil, now.minusMinutes(1)));
        assertEquals(1, taskMapper.acquire(task.getId(), "owner-b", now.plusMinutes(5), now));
        assertEquals(0, taskMapper.finishOwned(task.getId(), "owner-a", GrantBatchStatus.DONE, now));
        assertEquals(1, taskMapper.finishOwned(task.getId(), "owner-b", GrantBatchStatus.PARTIAL, now));
        assertEquals(GrantBatchStatus.PARTIAL, taskMapper.selectById(task.getId()).getStatus());
        taskService.scanDue();
        assertEquals(GrantBatchStatus.QUEUED, taskMapper.selectById(future.getId()).getStatus());
    }

    @Test
    void failedDailyAggregateDoesNotDropUvOnReplay() {
        OutboxEvent event = new OutboxEvent();
        event.setEventId("visit-" + UUID.randomUUID());
        event.setEventType(RedirectService.VISIT_EVENT);
        event.setAggregateId("42");
        event.setPayload("{\"linkId\":42,\"visitorId\":\"person-a\",\"occurredAt\":\"2026-11-07T23:50:00\"}");
        assertThrows(IllegalStateException.class, () -> visitStatsHandler.handle(event));
        assertEquals(0L, visitorMapper.selectCount(Wrappers.<LinkStatsVisitor>lambdaQuery().eq(LinkStatsVisitor::getLinkId, 42L)));
        assertNull(statsMapper.selectOne(Wrappers.<LinkStatsDaily>lambdaQuery().eq(LinkStatsDaily::getLinkId, 42L)));
        visitStatsHandler.handle(event);
        visitStatsHandler.handle(event);
        LinkStatsDaily daily = statsMapper.selectOne(Wrappers.<LinkStatsDaily>lambdaQuery().eq(LinkStatsDaily::getLinkId, 42L));
        assertEquals(LocalDate.of(2026, 11, 7), daily.getStatDate());
        assertEquals(1L, daily.getPv());
        assertEquals(1L, daily.getUv());
    }

    @Test
    void concurrentIdempotentCreateDoesNotLeaveAnExtraLink() throws Exception {
        Shop shop = shop();
        asMerchant(shop);
        var group = linkDirectoryService.createGroup("并发");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Link> first = new AtomicReference<>();
        AtomicReference<Link> second = new AtomicReference<>();
        pool.submit(call(start, shop, () -> first.set(linkDirectoryService.createLink(group.getGid(), "https://example.com/same", null, "once", null, null)), new AtomicInteger()));
        pool.submit(call(start, shop, () -> second.set(linkDirectoryService.createLink(group.getGid(), "https://example.com/same", null, "once", null, null)), new AtomicInteger()));
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(first.get().getId(), second.get().getId());
        assertEquals(1L, linkMapper.selectCount(Wrappers.<Link>lambdaQuery().eq(Link::getGid, group.getGid()).eq(Link::getDelTime, 0L)));
    }

    private Runnable call(CountDownLatch start, Shop shop, Runnable body, AtomicInteger failures) {
        return () -> {
            try {
                if (shop != null) {
                    asMerchant(shop);
                }
                start.await();
                body.run();
            } catch (RuntimeException exception) {
                failures.incrementAndGet();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                UserContext.clear();
            }
        };
    }

    private Shop shop() {
        IamMerchant merchant = new IamMerchant();
        merchant.setName("商家" + UUID.randomUUID());
        merchant.setStatus(IamStatus.ENABLED);
        merchantMapper.insert(merchant);
        IamShop row = new IamShop();
        row.setMerchantId(merchant.getId());
        row.setStoreCode("S" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        row.setName("店铺");
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

    @TestConfiguration
    static class FailOnce {
        private final AtomicInteger calls = new AtomicInteger();

        @Bean
        StatsApplyProbe statsApplyProbe() {
            return () -> {
                if (calls.getAndIncrement() == 0) {
                    throw new IllegalStateException("日统计写入失败");
                }
            };
        }
    }
}
