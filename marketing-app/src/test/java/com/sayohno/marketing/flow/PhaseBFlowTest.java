package com.sayohno.marketing.flow;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sayohno.marketing.common.config.MarketingProperties;
import com.sayohno.marketing.common.context.CurrentUser;
import com.sayohno.marketing.common.context.UserContext;
import com.sayohno.marketing.common.context.UserRole;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.common.outbox.ConsumerInbox;
import com.sayohno.marketing.common.outbox.ConsumerInboxMapper;
import com.sayohno.marketing.common.outbox.OutboxEvent;
import com.sayohno.marketing.common.outbox.OutboxEventMapper;
import com.sayohno.marketing.common.outbox.OutboxPublisher;
import com.sayohno.marketing.common.outbox.OutboxStore;
import com.sayohno.marketing.coupon.dao.entity.ClaimTicket;
import com.sayohno.marketing.coupon.dao.entity.GrantBatch;
import com.sayohno.marketing.coupon.dao.entity.IssuedVoucher;
import com.sayohno.marketing.coupon.dao.entity.OfferPlan;
import com.sayohno.marketing.coupon.dao.mapper.ClaimTicketMapper;
import com.sayohno.marketing.coupon.dao.mapper.GrantBatchMapper;
import com.sayohno.marketing.coupon.dao.mapper.IssuedVoucherMapper;
import com.sayohno.marketing.coupon.dao.mapper.OfferInventoryMapper;
import com.sayohno.marketing.coupon.dao.mapper.OfferPlanMapper;
import com.sayohno.marketing.coupon.enums.*;
import com.sayohno.marketing.coupon.service.BatchUserRow;
import com.sayohno.marketing.coupon.service.GrantBatchService;
import com.sayohno.marketing.coupon.service.OfferPlanService;
import com.sayohno.marketing.coupon.service.VoucherClaimService;
import com.sayohno.marketing.coupon.service.VoucherRedemptionService;
import com.sayohno.marketing.coupon.stock.OfferStockGateway;
import com.sayohno.marketing.identity.IamStatus;
import com.sayohno.marketing.identity.dao.entity.IamMerchant;
import com.sayohno.marketing.identity.dao.entity.IamShop;
import com.sayohno.marketing.identity.dao.entity.IamUser;
import com.sayohno.marketing.identity.dao.mapper.IamMerchantMapper;
import com.sayohno.marketing.identity.dao.mapper.IamShopMapper;
import com.sayohno.marketing.identity.dao.mapper.IamUserMapper;
import com.sayohno.marketing.shortlink.dao.entity.Link;
import com.sayohno.marketing.shortlink.dao.entity.LinkStatsDaily;
import com.sayohno.marketing.shortlink.dao.mapper.LinkStatsDailyMapper;
import com.sayohno.marketing.shortlink.mq.VisitStatsHandler;
import com.sayohno.marketing.shortlink.service.LinkDirectoryService;
import com.sayohno.marketing.shortlink.service.RedirectService;
import com.sayohno.marketing.support.MemoryStockConfiguration;
import com.sayohno.marketing.support.TestIdentityConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestIdentityConfiguration.class, MemoryStockConfiguration.class})
class PhaseBFlowTest {
    @Autowired
    private OfferPlanService templateService;
    @Autowired
    private VoucherClaimService claimService;
    @Autowired
    private VoucherRedemptionService settlementService;
    @Autowired
    private GrantBatchService taskService;
    @Autowired
    private OfferPlanMapper templateMapper;
    @Autowired
    private IssuedVoucherMapper userMapper;
    @Autowired
    private ClaimTicketMapper claimRequestMapper;
    @Autowired
    private GrantBatchMapper taskMapper;
    @Autowired
    private IamMerchantMapper merchantMapper;
    @Autowired
    private IamShopMapper shopMapper;
    @Autowired
    private LinkDirectoryService linkDirectoryService;
    @Autowired
    private RedirectService redirectService;
    @Autowired
    private LinkStatsDailyMapper statsMapper;
    @Autowired
    private OutboxPublisher outboxPublisher;
    @Autowired
    private OutboxEventMapper outboxEventMapper;
    @Autowired
    private VisitStatsHandler visitStatsHandler;
    @Autowired
    private ConsumerInboxMapper inboxMapper;
    @Autowired
    private IamUserMapper iamUserMapper;

    @Autowired
    private OfferStockGateway stockGateway;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private OfferInventoryMapper inventoryMapper;
    @Autowired
    private OutboxStore outboxStore;
    @Autowired
    private MarketingProperties properties;

    private VoucherClaimService worker(OfferStockGateway stock, PlatformTransactionManager manager) {
        return new VoucherClaimService(templateMapper, userMapper, claimRequestMapper, inventoryMapper,
                stock, outboxStore, properties, manager);
    }

    private OfferPlan claimPlan(int limit) {
        Shop shop = shop();
        asMerchant(shop);
        return templateService.create(shop.storeCode(), "领券恢复测试", IssuerScope.MERCHANT, ApplyScope.STOREWIDE,
                null, BenefitKind.AMOUNT_OFF, 2, LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusDays(1),
                "{\"maxClaimsPerUser\":" + limit + "}", "{\"capAmount\":\"1.00\"}");
    }

    @Test
    void staleWorkerCannotIssueOrCompensateAfterLeaseTakeover() throws Exception {
        OfferPlan plan = claimPlan(2);
        CountDownLatch reserved = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        PlatformTransactionManager paused = mock(PlatformTransactionManager.class, AdditionalAnswers.delegatesTo(transactions));
        AtomicBoolean firstCommit = new AtomicBoolean(true);
        doAnswer(call -> {
            transactions.commit(call.getArgument(0, TransactionStatus.class));
            if (firstCommit.getAndSet(false)) {
                // 第一笔事务已提交预扣，模拟执行者在开始发券前暂停。
                reserved.countDown();
                assertTrue(resume.await(10, TimeUnit.SECONDS));
            }
            return null;
        }).when(paused).commit(any());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            var old = pool.submit(() -> worker(stockGateway, paused).claim(710, plan.getId(), IssuerScope.MERCHANT, "takeover"));
            assertTrue(reserved.await(10, TimeUnit.SECONDS));
            ClaimTicket row = claimRequestMapper.selectOne(Wrappers.<ClaimTicket>lambdaQuery().eq(ClaimTicket::getPlanId, plan.getId()));
            LocalDateTime takeover = row.getLeaseUntil().plusSeconds(1);
            assertEquals(1, claimRequestMapper.acquireLease(row.getId(), "new-worker", LocalDateTime.now().minusMinutes(1), takeover));
            resume.countDown();
            assertTrue(assertThrows(ExecutionException.class, () -> old.get(10, TimeUnit.SECONDS)).getCause() instanceof BizException);
            assertEquals(1, stockGateway.readHold(row.getId()));
            assertEquals(2, templateMapper.selectById(plan.getId()).getInventoryQty());
            claimService.recoverStuck(Duration.ZERO);
            var voucher = claimService.claim(710, plan.getId(), IssuerScope.MERCHANT, "takeover");
            assertEquals(voucher.getId(), claimRequestMapper.selectById(row.getId()).getVoucherId());
            assertEquals(1, claimService.myCoupons(710, 1, 10).getTotal());
            assertEquals(1, templateMapper.selectById(plan.getId()).getInventoryQty());
        } finally {
            resume.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void cleanupFailurePreservesQuotaAndRetriesThroughSuccessEvent() {
        OfferPlan plan = claimPlan(1);
        OfferStockGateway failing = mock(OfferStockGateway.class, AdditionalAnswers.delegatesTo(stockGateway));
        doThrow(new IllegalStateException("模拟 Redis 删除超时")).when(failing).releaseHold(anyLong());
        IssuedVoucher voucher = worker(failing, transactions).claim(711, plan.getId(), IssuerScope.MERCHANT, "cleanup");
        ClaimTicket row = claimService.findRequestByUserCouponId(voucher.getId());
        assertEquals(1, stockGateway.readHold(row.getId()));
        assertEquals(voucher.getId(), claimService.claim(711, plan.getId(), IssuerScope.MERCHANT, "cleanup").getId());
        assertTrue(stockGateway.tryAcquire(plan.getId(), 711, 1, 2, 60, -711).quotaExceeded());
        assertEquals(1, templateMapper.selectById(plan.getId()).getInventoryQty());
        outboxPublisher.publishCycle();
        assertNull(stockGateway.readHold(row.getId()));
    }

    @AfterEach
    void clear() {
        UserContext.clear();
    }

    @Test
    void claimDoesNotOversellAndRepeatsTheSameRequest() throws Exception {
        Shop shop = shop();
        asMerchant(shop);
        OfferPlan template = templateService.create(shop.storeCode(), "满减", IssuerScope.MERCHANT, ApplyScope.STOREWIDE, null, BenefitKind.AMOUNT_OFF_OVER, 10,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusDays(2),
                "{\"maxClaimsPerUser\":2}",
                "{\"minSpend\":\"10.00\",\"capAmount\":\"3.00\",\"validityPeriodHours\":24}");
        AtomicInteger success = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(30);
        for (int i = 0; i < 30; i++) {
            long userId = 10_000 + i;
            pool.submit(() -> {
                try {
                    start.await();
                    claimService.claim(userId, template.getId(), IssuerScope.MERCHANT, "req-" + userId);
                    success.incrementAndGet();
                } catch (BizException ignored) {
                    // 库存耗尽或限领是预期失败
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(20, java.util.concurrent.TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(10, success.get());
        assertEquals(10, userMapper.selectCount(Wrappers.<IssuedVoucher>lambdaQuery().eq(IssuedVoucher::getPlanId, template.getId())));
        assertEquals(0, templateMapper.selectById(template.getId()).getInventoryQty());

        ClaimTicket saved = claimRequestMapper.selectOne(Wrappers.<ClaimTicket>lambdaQuery()
                .eq(ClaimTicket::getPlanId, template.getId())
                .eq(ClaimTicket::getStatus, ClaimTicketStatus.SUCCEEDED)
                .last("limit 1"));
        IssuedVoucher repeated = claimService.claim(saved.getUserId(), template.getId(), IssuerScope.MERCHANT, saved.getRequestId());
        assertEquals(saved.getVoucherId(), repeated.getId());
        assertThrows(BizException.class, () -> claimService.claim(saved.getUserId(), template.getId(), IssuerScope.PLATFORM, saved.getRequestId()));
        assertEquals(10, userMapper.selectCount(Wrappers.<IssuedVoucher>lambdaQuery().eq(IssuedVoucher::getPlanId, template.getId())));
    }

    @Test
    void settlementFollowsLockPayRefundAndCancel() {
        Shop shop = shop();
        asMerchant(shop);
        OfferPlan template = templateService.create(shop.storeCode(), "立减", IssuerScope.MERCHANT, ApplyScope.STOREWIDE, null, BenefitKind.AMOUNT_OFF, 5,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusDays(1),
                "{\"maxClaimsPerUser\":1}",
                "{\"minSpend\":\"20.00\",\"capAmount\":\"5.00\",\"validityPeriodHours\":48}");
        IssuedVoucher coupon = claimService.claim(88, template.getId(), IssuerScope.MERCHANT, "once");
        BigDecimal discount = settlementService.quote(88, coupon.getId(), new BigDecimal("30.00"), null);
        assertEquals(new BigDecimal("5.00"), discount);
        var locked = settlementService.lock(88, coupon.getId(), "order-1", new BigDecimal("30.00"), null);
        assertEquals(RedemptionStatus.HELD, locked.getStatus());
        assertThrows(BizException.class, () -> settlementService.lock(88, coupon.getId(), "order-2", new BigDecimal("30.00"), null));
        assertEquals(RedemptionStatus.HELD, settlementService.lock(88, coupon.getId(), "order-1", new BigDecimal("30.00"), null).getStatus());
        assertEquals(VoucherStatus.HELD, userMapper.selectById(coupon.getId()).getStatus());
        assertEquals(RedemptionStatus.COMPLETED, settlementService.pay(88, coupon.getId(), "order-1").getStatus());
        assertEquals(RedemptionStatus.COMPLETED, settlementService.pay(88, coupon.getId(), "order-1").getStatus());
        assertEquals(VoucherStatus.REDEEMED, userMapper.selectById(coupon.getId()).getStatus());
        assertThrows(BizException.class, () -> settlementService.cancel(88, coupon.getId(), "order-1"));
        assertEquals(RedemptionStatus.REFUNDED, settlementService.refund(88, coupon.getId(), "order-1").getStatus());
        assertEquals(RedemptionStatus.REFUNDED, settlementService.refund(88, coupon.getId(), "order-1").getStatus());
        assertEquals(VoucherStatus.AVAILABLE, userMapper.selectById(coupon.getId()).getStatus());
        outboxPublisher.publishDue(20);
        long inbox = inboxMapper.selectCount(Wrappers.<ConsumerInbox>lambdaQuery().eq(ConsumerInbox::getConsumerGroup, "coupon-event"));
        outboxPublisher.publishDue(20);
        assertEquals(inbox, inboxMapper.selectCount(Wrappers.<ConsumerInbox>lambdaQuery().eq(ConsumerInbox::getConsumerGroup, "coupon-event")));
    }

    @Test
    void stuckClaimCanBeCompletedOnce() {
        Shop shop = shop();
        asMerchant(shop);
        OfferPlan template = templateService.create(shop.storeCode(), "恢复", IssuerScope.MERCHANT, ApplyScope.STOREWIDE, null, BenefitKind.AMOUNT_OFF, 2,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusDays(1),
                "{\"maxClaimsPerUser\":1}", "{\"capAmount\":\"1.00\",\"validityPeriodHours\":1}");
        ClaimTicket request = new ClaimTicket();
        request.setUserId(501L);
        request.setPlanId(template.getId());
        request.setRequestId("stuck");
        request.setRequestHash("abc");
        request.setIssuerScope(IssuerScope.MERCHANT);
        request.setStatus(ClaimTicketStatus.RESERVED);
        request.setRedisApplied(1);
        request.setClaimSeq(1);
        request.setReservationToken("expired-worker");
        request.setLeaseUntil(LocalDateTime.now().minusMinutes(5));
        claimRequestMapper.insert(request);
        assertEquals(1, claimService.recoverStuck(Duration.ofSeconds(1)));
        assertEquals(1, templateMapper.selectById(template.getId()).getInventoryQty());
        assertEquals(ClaimTicketStatus.SUCCEEDED, claimRequestMapper.selectById(request.getId()).getStatus());
        assertEquals(0, claimService.recoverStuck(Duration.ofSeconds(1)));
    }

    @Test
    void shortLinkStaysInsideMerchantAndStatsDoNotDoubleCount() {
        Shop first = shop();
        Shop second = shop();
        asMerchant(first);
        var group = linkDirectoryService.createGroup("春季");
        Link link = linkDirectoryService.createLink(group.getGid(), "https://example.com/a", null, "channel-1", "evil.example", "https");
        Link again = linkDirectoryService.createLink(group.getGid(), "https://example.com/a", null, "channel-1", "evil.example", "https");
        assertEquals(link.getId(), again.getId());
        assertTrue(link.getFullShortUrl().startsWith("127.0.0.1:9100/s/"));
        assertThrows(BizException.class, () -> linkDirectoryService.createLink(group.getGid(), "https://example.com/b", null, "channel-1", null, null));
        Link resolved = redirectService.resolve(link.getShortUri(), "visitor-1");
        assertEquals("https://example.com/a", resolved.getOriginUrl());
        OutboxEvent visit = outboxEventMapper.selectOne(Wrappers.<OutboxEvent>lambdaQuery()
                .eq(OutboxEvent::getEventType, RedirectService.VISIT_EVENT)
                .eq(OutboxEvent::getAggregateId, String.valueOf(link.getId())));
        visitStatsHandler.handle(visit);
        visitStatsHandler.handle(visit);
        LinkStatsDaily stats = statsMapper.selectOne(Wrappers.<LinkStatsDaily>lambdaQuery().eq(LinkStatsDaily::getLinkId, link.getId()));
        assertEquals(1L, stats.getPv());
        assertEquals(1L, stats.getUv());
        linkDirectoryService.recycle(link.getId());
        assertThrows(BizException.class, () -> redirectService.resolve(link.getShortUri(), "visitor-1"));
        asMerchant(second);
        assertThrows(BizException.class, () -> linkDirectoryService.listLinks(group.getGid(), false));
    }

    @Test
    void batchTaskIsIdempotentAndKeepsFailureRows() throws Exception {
        Shop shop = shop();
        asMerchant(shop);
        OfferPlan template = templateService.create(shop.storeCode(), "批量", IssuerScope.MERCHANT, ApplyScope.STOREWIDE, null, BenefitKind.AMOUNT_OFF, 1,
                LocalDateTime.now().minusMinutes(1), LocalDateTime.now().plusDays(1),
                "{\"maxClaimsPerUser\":1}", "{\"capAmount\":\"1.00\",\"validityPeriodHours\":2}");
        IamUser buyer = new IamUser();
        buyer.setId(701L);
        buyer.setUsername("buyer701" + shop.storeCode());
        buyer.setPasswordHash("hash");
        buyer.setStatus(1);
        iamUserMapper.insert(buyer);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        EasyExcel.write(output, BatchUserRow.class).autoCloseStream(false).sheet().doWrite(List.of(row("701"), row("bad"), row("701")));
        GrantBatch task = taskService.create(template.getId(), output.toByteArray(), "users.xlsx");
        assertArrayEquals(output.toByteArray(), taskMapper.loadInput(task.getId()).contents());
        assertEquals(1, taskService.scanDue());
        assertNull(taskMapper.loadInput(task.getId()));
        GrantBatch done = taskMapper.selectById(task.getId());
        assertEquals(1, done.getSuccessCount());
        assertEquals(2, done.getFailCount());
        assertEquals(3, done.getTotalCount());
        assertEquals(0, taskService.scanDue());
        String token = "other-worker";
        assertEquals(0, taskMapper.acquire(done.getId(), token, LocalDateTime.now().plusMinutes(2), LocalDateTime.now()));
    }

    private static BatchUserRow row(String userId) {
        BatchUserRow row = new BatchUserRow();
        row.setUserId(userId);
        return row;
    }

    private Shop shop() {
        IamMerchant merchant = new IamMerchant();
        merchant.setName("商家" + UUID.randomUUID());
        merchant.setStatus(IamStatus.ENABLED);
        merchantMapper.insert(merchant);
        IamShop shop = new IamShop();
        shop.setMerchantId(merchant.getId());
        shop.setStoreCode("S" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        shop.setName("店铺");
        shop.setStatus(IamStatus.ENABLED);
        shopMapper.insert(shop);
        return new Shop(merchant.getId(), shop.getStoreCode());
    }

    private void asMerchant(Shop shop) {
        UserContext.set(CurrentUser.builder()
                .userId(shop.merchantId())
                .username("owner")
                .merchantId(shop.merchantId())
                .storeCode(shop.storeCode())
                .roles(Set.of(UserRole.OWNER, UserRole.USER))
                .build());
    }

    private record Shop(long merchantId, String storeCode) {
    }
}
