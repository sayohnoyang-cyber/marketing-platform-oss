package com.sayohno.marketing.flow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sayohno.marketing.common.outbox.ConsumerInbox;
import com.sayohno.marketing.common.outbox.ConsumerInboxMapper;
import com.sayohno.marketing.common.outbox.InboxGuard;
import com.sayohno.marketing.common.outbox.OutboxEvent;
import com.sayohno.marketing.common.outbox.OutboxEventMapper;
import com.sayohno.marketing.common.outbox.OutboxPublisher;
import com.sayohno.marketing.coupon.dao.entity.ClaimTicket;
import com.sayohno.marketing.coupon.dao.entity.OfferInventory;
import com.sayohno.marketing.coupon.dao.entity.OfferPlan;
import com.sayohno.marketing.coupon.dao.mapper.ClaimTicketMapper;
import com.sayohno.marketing.coupon.dao.mapper.IssuedVoucherMapper;
import com.sayohno.marketing.coupon.dao.mapper.OfferInventoryMapper;
import com.sayohno.marketing.coupon.dao.mapper.OfferPlanMapper;
import com.sayohno.marketing.coupon.enums.ApplyScope;
import com.sayohno.marketing.coupon.enums.BenefitKind;
import com.sayohno.marketing.coupon.enums.ClaimTicketStatus;
import com.sayohno.marketing.coupon.enums.InventoryStatus;
import com.sayohno.marketing.coupon.enums.IssuerScope;
import com.sayohno.marketing.coupon.enums.OfferPlanStatus;
import com.sayohno.marketing.coupon.mq.VoucherEventHandler;
import com.sayohno.marketing.coupon.service.VoucherClaimService;
import com.sayohno.marketing.coupon.stock.OfferStockGateway;
import com.sayohno.marketing.support.MemoryStockConfiguration;
import com.sayohno.marketing.support.TestIdentityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异步领券事务边界与异常分类重试测试：
 * 1. 验证消费者处理 async claim 时不处于外层事务中；
 * 2. 验证瞬时数据库异常触发重试且不回补 Redis 库存、不将票据标为 FAILED；
 * 3. 验证终态业务失败回补 Redis 库存并标记 FAILED。
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({TestIdentityConfiguration.class, MemoryStockConfiguration.class})
class VoucherAsyncClaimConsistencyTest {
    @Autowired
    private VoucherClaimService claimService;
    @Autowired
    private VoucherEventHandler voucherEventHandler;
    @Autowired
    private OfferPlanMapper planMapper;
    @Autowired
    private OfferInventoryMapper inventoryMapper;
    @Autowired
    private ClaimTicketMapper ticketMapper;
    @Autowired
    private IssuedVoucherMapper voucherMapper;
    @Autowired
    private OutboxEventMapper outboxMapper;
    @Autowired
    private ConsumerInboxMapper inboxMapper;
    @Autowired
    private OutboxPublisher outboxPublisher;
    @Autowired
    private InboxGuard inboxGuard;
    @Autowired
    private OfferStockGateway stockGateway;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void asyncClaimRunsWithoutOuterTxAndRetriesTransientErrorThenSucceeds() {
        OfferPlan plan = insertPlan(10, 2);
        OfferInventory inventory = insertInventory(plan.getId(), 5, 2);
        String requestId = "async-ok-" + UUID.randomUUID();

        VoucherClaimService.ClaimReceipt receipt = claimService.claimAllocationAsync(
                7001L, plan.getId(), inventory.getId(), IssuerScope.MERCHANT, requestId);
        assertEquals("RESERVED", receipt.status());

        ClaimTicket ticket = ticketMapper.selectOne(Wrappers.<ClaimTicket>lambdaQuery()
                .eq(ClaimTicket::getUserId, 7001L)
                .eq(ClaimTicket::getInventoryId, inventory.getId())
                .eq(ClaimTicket::getRequestId, requestId));
        assertNotNull(ticket);
        assertNotNull(stockGateway.readAllocationHold(inventory.getId(), ticket.getId()));

        // 验证禁止在外层事务中调用 completeAsyncClaim
        transactionTemplate.executeWithoutResult(status -> assertThrows(IllegalStateException.class,
                () -> claimService.completeAsyncClaim(ticket.getId(), ticket.getReservationToken())));
        assertTrue(!TransactionSynchronizationManager.isActualTransactionActive());

        // 通过 OutboxPublisher 投递，验证独立事务提交且 hold 已释放、Inbox 已登记
        assertTrue(outboxPublisher.publishDue(20) >= 1);
        ClaimTicket done = ticketMapper.selectById(ticket.getId());
        assertEquals(ClaimTicketStatus.SUCCEEDED, done.getStatus());
        assertNotNull(done.getVoucherId());
        assertNull(stockGateway.readAllocationHold(inventory.getId(), ticket.getId()));
        assertNotNull(voucherMapper.selectById(done.getVoucherId()));

        OutboxEvent event = outboxMapper.selectOne(Wrappers.<OutboxEvent>lambdaQuery()
                .eq(OutboxEvent::getEventType, VoucherClaimService.ASYNC_CLAIM_EVENT)
                .eq(OutboxEvent::getAggregateId, String.valueOf(ticket.getId())));
        assertNotNull(event);
        assertEquals(1L, inboxMapper.selectCount(Wrappers.<ConsumerInbox>lambdaQuery()
                .eq(ConsumerInbox::getConsumerGroup, "coupon-event")
                .eq(ConsumerInbox::getEventId, event.getEventId())));
    }

    @Test
    void transientFailureThrowsForRetryWithoutReleasingHoldAndPermanentFailureCompensates() {
        OfferPlan plan = insertPlan(10, 2);
        OfferInventory inventory = insertInventory(plan.getId(), 5, 2);
        String requestId = "async-retry-" + UUID.randomUUID();

        claimService.claimAllocationAsync(7002L, plan.getId(), inventory.getId(), IssuerScope.MERCHANT, requestId);
        ClaimTicket ticket = ticketMapper.selectOne(Wrappers.<ClaimTicket>lambdaQuery()
                .eq(ClaimTicket::getUserId, 7002L)
                .eq(ClaimTicket::getInventoryId, inventory.getId())
                .eq(ClaimTicket::getRequestId, requestId));
        assertNotNull(ticket);

        // 模拟外层消费者投递时遇到瞬时异常：不写 Inbox、不释放 hold、保持 RESERVED 以便重试
        OutboxEvent transientEvent = new OutboxEvent();
        transientEvent.setEventId("transient-" + UUID.randomUUID());
        transientEvent.setEventType(VoucherClaimService.ASYNC_CLAIM_EVENT);
        transientEvent.setAggregateId(String.valueOf(ticket.getId()));
        transientEvent.setPayload("{\"requestRowId\":" + ticket.getId() + ",\"token\":\"" + ticket.getReservationToken() + "\"}");

        // 验证瞬时异常：onceWithoutOuterTx 应向上抛出异常且不写入 Inbox，保留重试资格
        String transientId = "transient-throw-" + UUID.randomUUID();
        OutboxEvent retryEvent = new OutboxEvent();
        retryEvent.setEventId(transientId);
        retryEvent.setEventType(VoucherClaimService.ASYNC_CLAIM_EVENT);
        retryEvent.setAggregateId("0");
        retryEvent.setPayload("{}");
        assertThrows(TransientDataAccessResourceException.class,
                () -> inboxGuard.onceWithoutOuterTx("coupon-event", retryEvent, ignored -> {
                    throw new TransientDataAccessResourceException("simulated db timeout");
                }));
        assertEquals(0L, inboxMapper.selectCount(Wrappers.<ConsumerInbox>lambdaQuery()
                .eq(ConsumerInbox::getConsumerGroup, "coupon-event")
                .eq(ConsumerInbox::getEventId, transientId)));

        // 关闭投放模拟终态业务异常（claimOne 返回 0 -> VALIDATION）：应回补 Redis 并标 FAILED，同时登记 Inbox
        inventoryMapper.freeze(inventory.getId(), LocalDateTime.now());
        voucherEventHandler.handle(transientEvent);
        ClaimTicket failed = ticketMapper.selectById(ticket.getId());
        assertEquals(ClaimTicketStatus.FAILED, failed.getStatus());
        assertNull(stockGateway.readAllocationHold(inventory.getId(), ticket.getId()));

        // 验证 Inbox 已标记消费（终态补偿成功），不会重复消费
        assertEquals(1L, inboxMapper.selectCount(Wrappers.<ConsumerInbox>lambdaQuery()
                .eq(ConsumerInbox::getConsumerGroup, "coupon-event")
                .eq(ConsumerInbox::getEventId, transientEvent.getEventId())));
    }

    private OfferPlan insertPlan(int stock, int maxClaimsPerUser) {
        LocalDateTime now = LocalDateTime.now();
        OfferPlan plan = new OfferPlan();
        plan.setMerchantId(1001L);
        plan.setTitle("异步一致性测试券");
        plan.setIssuerScope(IssuerScope.MERCHANT);
        plan.setApplyScope(ApplyScope.STOREWIDE);
        plan.setBenefitKind(BenefitKind.AMOUNT_OFF);
        plan.setInventoryQty(stock);
        plan.setIssuedTotal(stock);
        plan.setUsableFrom(now.minusHours(1));
        plan.setUsableUntil(now.plusDays(1));
        plan.setClaimPolicy("{\"maxClaimsPerUser\":" + maxClaimsPerUser + "}");
        plan.setBenefitPolicy("{\"capAmount\":\"5.00\",\"validityPeriodHours\":24}");
        plan.setStatus(OfferPlanStatus.OPEN);
        planMapper.insert(plan);
        return plan;
    }

    private OfferInventory insertInventory(long planId, int qty, int maxClaimsPerUser) {
        OfferInventory inventory = new OfferInventory();
        inventory.setPlanId(planId);
        inventory.setMerchantId(1001L);
        inventory.setQuantity(qty);
        inventory.setClaimedCount(0);
        inventory.setMaxClaimsPerUser(maxClaimsPerUser);
        inventory.setStatus(InventoryStatus.OPEN);
        inventory.setIdempotencyKey("inv-" + UUID.randomUUID());
        inventoryMapper.insert(inventory);
        return inventory;
    }
}
