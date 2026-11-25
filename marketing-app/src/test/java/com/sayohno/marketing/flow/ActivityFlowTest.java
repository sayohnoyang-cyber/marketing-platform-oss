package com.sayohno.marketing.flow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sayohno.marketing.campaign.dao.entity.CampaignChannel;
import com.sayohno.marketing.campaign.dao.entity.CampaignVisit;
import com.sayohno.marketing.campaign.dao.mapper.CampaignActivityMapper;
import com.sayohno.marketing.campaign.dao.mapper.CampaignVisitMapper;
import com.sayohno.marketing.campaign.service.CampaignFlowService;
import com.sayohno.marketing.campaign.service.CampaignOperationStore;
import com.sayohno.marketing.campaign.service.CampaignPreparationService;
import com.sayohno.marketing.campaign.service.CampaignService;
import com.sayohno.marketing.campaign.service.DataRetentionService;
import com.sayohno.marketing.common.context.CurrentUser;
import com.sayohno.marketing.common.context.UserContext;
import com.sayohno.marketing.common.context.UserRole;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.coupon.enums.*;
import com.sayohno.marketing.coupon.dao.entity.OfferPlan;
import com.sayohno.marketing.coupon.service.OfferPlanService;
import com.sayohno.marketing.identity.IamStatus;
import com.sayohno.marketing.identity.dao.entity.IamMerchant;
import com.sayohno.marketing.identity.dao.entity.IamShop;
import com.sayohno.marketing.identity.dao.mapper.IamMerchantMapper;
import com.sayohno.marketing.identity.dao.mapper.IamShopMapper;
import com.sayohno.marketing.shortlink.dao.entity.Link;
import com.sayohno.marketing.shortlink.dao.mapper.LinkMapper;
import com.sayohno.marketing.shortlink.service.LinkDirectoryService;
import com.sayohno.marketing.shortlink.service.RedirectService;
import com.sayohno.marketing.support.MemoryStockConfiguration;
import com.sayohno.marketing.support.TestIdentityConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.sayohno.marketing.common.outbox.OutboxPublisher;
import com.sayohno.marketing.common.outbox.OutboxStore;
import com.sayohno.marketing.coupon.dao.entity.ClaimTicket;
import com.sayohno.marketing.coupon.dao.mapper.ClaimTicketMapper;
import com.sayohno.marketing.coupon.dao.mapper.OfferPlanMapper;
import com.sayohno.marketing.coupon.service.VoucherClaimService;
import com.sayohno.marketing.coupon.stock.OfferStockGateway;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestIdentityConfiguration.class, MemoryStockConfiguration.class})
class ActivityFlowTest {
    @Autowired
    private CampaignService campaignService;
    @Autowired
    private CampaignFlowService flowService;
    @Autowired
    private CampaignPreparationService preparationService;
    @Autowired
    private CampaignOperationStore operationStore;
    @Autowired
    private DataRetentionService dataRetentionService;
    @Autowired
    private OfferPlanService templateService;
    @Autowired
    private IamMerchantMapper merchantMapper;
    @Autowired
    private IamShopMapper shopMapper;
    @Autowired
    private LinkMapper linkMapper;
    @Autowired
    private LinkDirectoryService linkDirectoryService;
    @Autowired
    private RedirectService redirectService;
    @Autowired
    private CampaignVisitMapper visitMapper;
    @Autowired
    private CampaignActivityMapper activityMapper;
    @Autowired
    private VoucherClaimService claimService;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private OutboxPublisher outboxPublisher;
    @Autowired
    private OutboxStore outboxStore;
    @Autowired
    private ClaimTicketMapper claimTicketMapper;
    @Autowired
    private OfferPlanMapper offerPlanMapper;
    @Autowired
    private OfferStockGateway stockGateway;

    @AfterEach
    void clear() {
        UserContext.clear();
    }

    @Test
    void activityClaimFollowsServerTimeAndChannelCredential() {
        Shop shop = shop();
        asMerchant(shop);
        LocalDateTime now = LocalDateTime.now();
        OfferPlan template = templateService.create(shop.storeCode(), "活动券", IssuerScope.MERCHANT, ApplyScope.STOREWIDE, null, BenefitKind.AMOUNT_OFF, 5,
                now.minusDays(1), now.plusDays(3),
                "{\"maxClaimsPerUser\":1}", "{\"capAmount\":\"1.00\",\"validityPeriodHours\":24}");
        var activity = campaignService.create("冬日有礼", now.minusHours(2), now.plusHours(2), now.plusDays(1));
        campaignService.attachCoupon(activity.getId(), template.getId());
        flowService.place(activity.getId(), 2, 1, "alloc-1", new BigDecimal("10.00"));
        campaignService.assertDirectClaimAllowed(template.getId());
        CampaignChannel first = flowService.addChannel(activity.getId(), "微信", "ch-1");
        CampaignChannel again = flowService.addChannel(activity.getId(), "微信", "ch-1");
        CampaignChannel second = flowService.addChannel(activity.getId(), "短信", "ch-2");
        assertEquals(first.getLinkId(), again.getLinkId());
        assertEquals(first.getLinkId(), second.getLinkId());
        assertEquals(1, linkMapper.selectCount(Wrappers.<Link>lambdaQuery().eq(Link::getMerchantId, shop.merchantId())));
        campaignService.publish(activity.getId());
        assertThrows(BizException.class, () -> campaignService.assertDirectClaimAllowed(template.getId()));

        Link link = linkMapper.selectById(first.getLinkId());
        Map<String, Object> entry = flowService.entry(link.getShortUri(), first.getChannelCode(), "visitor-a");
        String credential = (String) entry.get("credential");
        assertEquals("WARMUP", entry.get("phase"));
        assertEquals(template.getTitle(), entry.get("couponName"));
        assertEquals(template.getBenefitPolicy(), entry.get("couponRule"));
        assertEquals(template.getUsableUntil(), entry.get("couponValidEndTime"));
        assertEquals(1, entry.get("maxClaimsPerUser"));
        org.junit.jupiter.api.Assertions.assertFalse(entry.containsKey("merchantId"));
        org.junit.jupiter.api.Assertions.assertFalse(entry.containsKey("stock"));
        assertThrows(BizException.class, () -> templateService.publicOffer(template.getId(), shop.merchantId() + 1));
        flowService.reserve(9, activity.getId(), credential);
        assertEquals(true, flowService.personalState(activity.getId(), 9).get("personalReserved"));
        assertEquals(false, flowService.personalState(activity.getId(), 10).get("personalReserved"));
        flowService.reserve(9, activity.getId(), credential);
        assertEquals(1, flowService.activeReservations(activity.getId()));
        flowService.cancelReservation(9, activity.getId());
        assertEquals(0, flowService.activeReservations(activity.getId()));
        assertEquals(false, flowService.personalState(activity.getId(), 9).get("personalReserved"));
        flowService.reserve(9, activity.getId(), credential);
        assertEquals(1, flowService.activeReservations(activity.getId()));
        assertThrows(BizException.class, () -> flowService.claim(9, activity.getId(), template.getId(), "req-1", credential));

        campaignService.postpone(activity.getId(), now.minusHours(3), now.minusMinutes(5), now.plusDays(2));
        assertEquals(now.plusDays(2), linkMapper.selectById(first.getLinkId()).getValidDate());
        var claimed = flowService.claim(9, activity.getId(), template.getId(), "req-1", credential);
        var repeated = flowService.claim(9, activity.getId(), template.getId(), "req-1", credential);
        assertEquals(claimed.getId(), repeated.getId());
        assertEquals(1L, flowService.personalState(activity.getId(), 9).get("personalClaimed"));
        assertEquals(0L, flowService.personalState(activity.getId(), 10).get("personalClaimed"));
        long visitsBefore = visitMapper.selectCount(Wrappers.<CampaignVisit>lambdaQuery().eq(CampaignVisit::getActivityId, activity.getId()));
        var controller = new com.sayohno.marketing.campaign.web.ActivityEntryController(flowService);
        var statusRequest = new com.sayohno.marketing.campaign.web.dto.EntryRequest();
        statusRequest.setChannelCode(second.getChannelCode());
        UserContext.set(CurrentUser.builder().userId(9L).roles(Set.of(UserRole.USER)).build());
        var restored = (Map<?, ?>) controller.status(activity.getId(), statusRequest).getData();
        assertEquals(true, restored.get("personalReserved"));
        assertEquals(1L, restored.get("personalClaimed"));
        org.junit.jupiter.api.Assertions.assertFalse(restored.containsKey("credential"));
        UserContext.clear();
        var anonymous = (Map<?, ?>) controller.status(activity.getId(), statusRequest).getData();
        assertEquals(0L, anonymous.get("personalClaimed"));
        assertEquals(false, anonymous.get("personalReserved"));
        assertEquals(visitsBefore, visitMapper.selectCount(Wrappers.<CampaignVisit>lambdaQuery().eq(CampaignVisit::getActivityId, activity.getId())));
        asMerchant(shop);
        assertEquals(1L, flowService.report(activity.getId()).stream()
                .filter(row -> first.getId().equals(row.get("channelId"))).findFirst().orElseThrow().get("claims"));
        campaignService.pause(activity.getId());
        assertThrows(BizException.class, () -> flowService.claim(9, activity.getId(), template.getId(), "req-2", credential));
        assertThrows(BizException.class, () -> flowService.claim(9, activity.getId(), template.getId(), "req-2", "forged-token"));

        CampaignVisit visit = visitMapper.selectOne(Wrappers.<CampaignVisit>lambdaQuery()
                .eq(CampaignVisit::getActivityId, activity.getId()));
        visit.setExpireAt(now.minusMinutes(1));
        visitMapper.updateById(visit);
        campaignService.publish(activity.getId());
        assertThrows(BizException.class, () -> flowService.claim(9, activity.getId(), template.getId(), "req-3", credential));
    }

    @Test
    void claimRefusesToRunInsideOuterTransaction() {
        // 外层事务会把落库推迟到 Redis 预扣、释放之后，回滚时库存就对不上，所以领券必须拒绝被事务包裹。
        var outer = new TransactionTemplate(transactionManager);
        assertThrows(IllegalStateException.class,
                () -> outer.executeWithoutResult(status -> claimService.claim(1, 1, IssuerScope.MERCHANT, "outer-tx")));
    }

    @Test
    void offlineKeepsAllocationAndMainLinkDoesNotNeedAChannel() {
        Shop shop = shop();
        asMerchant(shop);
        LocalDateTime now = LocalDateTime.now();
        OfferPlan template = templateService.create(shop.storeCode(), "百张券", IssuerScope.MERCHANT, ApplyScope.STOREWIDE, null, BenefitKind.AMOUNT_OFF, 100,
                now.minusDays(1), now.plusDays(3),
                "{\"maxClaimsPerUser\":2}", "{\"capAmount\":\"1.00\",\"validityPeriodHours\":24}");
        var activity = campaignService.create("活动A", now.minusHours(1), now.plusHours(3), now.plusDays(1));
        Map<String, Object> main = flowService.openMain(activity.getId());
        org.junit.jupiter.api.Assertions.assertFalse(String.valueOf(main.get("shortUri")).isBlank());
        flowService.saveOffer(activity.getId(), template.getId(), 60, 1, "offer-a", new BigDecimal("60.00"), null);
        assertEquals(40, templateService.requireOwned(template.getId()).getInventoryQty());
        var other = campaignService.create("活动B", now.minusHours(1), now.plusHours(3), now.plusDays(1));
        campaignService.attachCoupon(other.getId(), template.getId());
        assertThrows(BizException.class, () -> flowService.place(other.getId(), 50, 1, "offer-b", new BigDecimal("50.00")));
        campaignService.publish(activity.getId());
        Map<String, Object> direct = flowService.entry(String.valueOf(main.get("shortUri")), null, "visitor-direct");
        assertEquals("DIRECT", direct.get("source"));
        assertEquals("RESERVE", direct.get("action"));
        campaignService.offline(activity.getId());
        assertEquals(40, templateService.requireOwned(template.getId()).getInventoryQty());
        Map<String, Object> blocked = flowService.entry(String.valueOf(main.get("shortUri")), null, "visitor-offline");
        assertEquals("UNAVAILABLE", blocked.get("action"));
        assertEquals("活动已下线", blocked.get("reason"));
        campaignService.publish(activity.getId());
        assertEquals("RESERVE", flowService.entry(String.valueOf(main.get("shortUri")), null, "visitor-back").get("action"));
        campaignService.cancel(activity.getId());
        assertEquals(100, templateService.requireOwned(template.getId()).getInventoryQty());
    }

    @Test
    void asyncClaimPersistsCouponAndAttributionViaOutbox() {
        Shop shop = shop();
        asMerchant(shop);
        LocalDateTime now = LocalDateTime.now();
        OfferPlan template = templateService.create(shop.storeCode(), "异步秒杀券", IssuerScope.MERCHANT, ApplyScope.STOREWIDE, null, BenefitKind.AMOUNT_OFF, 10,
                now.minusDays(1), now.plusDays(3),
                "{\"maxClaimsPerUser\":1}", "{\"capAmount\":\"1.00\",\"validityPeriodHours\":24}");
        var activity = campaignService.create("异步秒杀", now.minusHours(2), now.minusMinutes(5), now.plusDays(1));
        campaignService.attachCoupon(activity.getId(), template.getId());
        flowService.place(activity.getId(), 5, 1, "async-alloc", new BigDecimal("50.00"));
        CampaignChannel channel = flowService.addChannel(activity.getId(), "社群", "async-ch");
        campaignService.publish(activity.getId());
        Link link = linkMapper.selectById(channel.getLinkId());
        Map<String, Object> entry = flowService.entry(link.getShortUri(), channel.getChannelCode(), "visitor-async");
        String credential = (String) entry.get("credential");

        // 第一步：异步接口落库 claim_ticket 与 Outbox 事件，立即返回 RESERVED
        Map<String, Object> accepted = flowService.claimAsync(77L, activity.getId(), template.getId(), "async-req-1", credential);
        assertEquals("RESERVED", accepted.get("status"));
        assertEquals("RESERVED", flowService.claimStatus(77L, activity.getId(), template.getId(), "async-req-1").get("status"));

        // 第二步：由 Outbox 消费完成落库与归因回填（不依赖进程内队列）
        outboxPublisher.publishDue(20);
        outboxPublisher.publishDue(20);
        Map<String, Object> done = flowService.claimStatus(77L, activity.getId(), template.getId(), "async-req-1");
        assertEquals("SUCCESS", done.get("status"));
        org.junit.jupiter.api.Assertions.assertNotNull(done.get("voucherId"));
        assertEquals(1L, flowService.personalState(activity.getId(), 77L).get("personalClaimed"));
        asMerchant(shop);
        assertEquals(1L, flowService.report(activity.getId()).stream()
                .filter(row -> channel.getId().equals(row.get("channelId"))).findFirst().orElseThrow().get("claims"));

        // 活动暂停后，用户仍可正常查询已提交的异步领券结果（P0-4）
        campaignService.pause(activity.getId());
        Map<String, Object> afterPause = flowService.claimStatus(77L, activity.getId(), template.getId(), "async-req-1");
        assertEquals("SUCCESS", afterPause.get("status"));
        assertEquals(done.get("voucherId"), afterPause.get("voucherId"));
    }

    @Test
    void asyncClaimRecoversAfterOutboxMissedAndLeaseExpired() {
        Shop shop = shop();
        asMerchant(shop);
        LocalDateTime now = LocalDateTime.now();
        OfferPlan template = templateService.create(shop.storeCode(), "恢复券", IssuerScope.MERCHANT, ApplyScope.STOREWIDE, null, BenefitKind.AMOUNT_OFF, 10,
                now.minusDays(1), now.plusDays(3),
                "{\"maxClaimsPerUser\":1}", "{\"capAmount\":\"1.00\",\"validityPeriodHours\":24}");
        var activity = campaignService.create("宕机恢复活动", now.minusHours(2), now.minusMinutes(5), now.plusDays(1));
        campaignService.attachCoupon(activity.getId(), template.getId());
        var offer = flowService.place(activity.getId(), 5, 1, "rec-alloc", new BigDecimal("50.00"));
        flowService.openMain(activity.getId());
        campaignService.publish(activity.getId());
        Link link = linkMapper.selectById(campaignService.requireOwned(activity.getId()).getMainLinkId());
        String credential = (String) flowService.entry(link.getShortUri(), null, "visitor-rec").get("credential");

        Map<String, Object> accepted = flowService.claimAsync(88L, activity.getId(), template.getId(), "rec-req-1", credential);
        assertEquals("RESERVED", accepted.get("status"));

        // 模拟进程重启/Outbox 尚未消费且租约过期
        ClaimTicket ticket = claimTicketMapper.selectOne(Wrappers.<ClaimTicket>lambdaQuery()
                .eq(ClaimTicket::getUserId, 88L)
                .eq(ClaimTicket::getInventoryId, offer.getInventoryId())
                .eq(ClaimTicket::getRequestId, "rec-req-1"));
        ticket.setLeaseUntil(now.minusMinutes(5));
        claimTicketMapper.updateById(ticket);

        int recovered = claimService.recoverStuck(Duration.ZERO);
        org.junit.jupiter.api.Assertions.assertTrue(recovered >= 1);
        Map<String, Object> status = flowService.claimStatus(88L, activity.getId(), template.getId(), "rec-req-1");
        assertEquals("SUCCESS", status.get("status"));
        org.junit.jupiter.api.Assertions.assertNotNull(status.get("voucherId"));
    }

    @Test
    void asyncClaimCompensatesWhenTemplateClosedBeforeCommit() {
        Shop shop = shop();
        asMerchant(shop);
        LocalDateTime now = LocalDateTime.now();
        OfferPlan template = templateService.create(shop.storeCode(), "关闭补偿券", IssuerScope.MERCHANT, ApplyScope.STOREWIDE, null, BenefitKind.AMOUNT_OFF, 10,
                now.minusDays(1), now.plusDays(3),
                "{\"maxClaimsPerUser\":1}", "{\"capAmount\":\"1.00\",\"validityPeriodHours\":24}");
        var activity = campaignService.create("补偿测试", now.minusHours(2), now.minusMinutes(5), now.plusDays(1));
        campaignService.attachCoupon(activity.getId(), template.getId());
        var offer = flowService.place(activity.getId(), 5, 1, "comp-alloc", new BigDecimal("50.00"));
        flowService.openMain(activity.getId());
        campaignService.publish(activity.getId());
        Link link = linkMapper.selectById(campaignService.requireOwned(activity.getId()).getMainLinkId());
        String credential = (String) flowService.entry(link.getShortUri(), null, "visitor-comp").get("credential");

        Map<String, Object> accepted = flowService.claimAsync(99L, activity.getId(), template.getId(), "comp-req-1", credential);
        assertEquals("RESERVED", accepted.get("status"));
        ClaimTicket ticket = claimTicketMapper.selectOne(Wrappers.<ClaimTicket>lambdaQuery()
                .eq(ClaimTicket::getUserId, 99L)
                .eq(ClaimTicket::getInventoryId, offer.getInventoryId())
                .eq(ClaimTicket::getRequestId, "comp-req-1"));
        org.junit.jupiter.api.Assertions.assertNotNull(stockGateway.readAllocationHold(offer.getInventoryId(), ticket.getId()));

        // 消费前将券模板置为非 OPEN，异步落库应标记 FAILED 并回补 Redis hold
        template.setStatus(OfferPlanStatus.CLOSED);
        offerPlanMapper.updateById(template);
        outboxPublisher.publishDue(20);

        Map<String, Object> failed = flowService.claimStatus(99L, activity.getId(), template.getId(), "comp-req-1");
        assertEquals("FAILED", failed.get("status"));
        org.junit.jupiter.api.Assertions.assertNull(stockGateway.readAllocationHold(offer.getInventoryId(), ticket.getId()));
    }

    @Test
    void outboxPublishCycleDrainsMultipleBatchesInSingleRound() {
        String prefix = "cycle-" + UUID.randomUUID() + "-";
        int total = 450;
        for (int i = 0; i < total; i++) {
            outboxStore.append(prefix + i, "campaign.conversion", "999",
                    "{\"eventType\":\"CLAIM\",\"voucherId\":" + (100000 + i) + "}");
        }
        int delivered = outboxPublisher.publishCycle();
        org.junit.jupiter.api.Assertions.assertTrue(delivered >= total);
    }

    @Test
    void pageCacheServesStatusAndInvalidatesOnChannelOrActivityChange() {
        Shop shop = shop();
        asMerchant(shop);
        LocalDateTime now = LocalDateTime.now();
        OfferPlan template = templateService.create(shop.storeCode(), "缓存测试券", IssuerScope.MERCHANT, ApplyScope.STOREWIDE, null, BenefitKind.AMOUNT_OFF, 10,
                now.minusDays(1), now.plusDays(3),
                "{\"maxClaimsPerUser\":1}", "{\"capAmount\":\"1.00\",\"validityPeriodHours\":24}");
        var activity = campaignService.create("读缓存测试", now.minusHours(2), now.minusMinutes(5), now.plusDays(1));
        campaignService.attachCoupon(activity.getId(), template.getId());
        flowService.place(activity.getId(), 5, 1, "cache-alloc", new BigDecimal("50.00"));
        CampaignChannel channel = flowService.addChannel(activity.getId(), "公众号", "cache-ch");
        campaignService.publish(activity.getId());

        flowService.warmPageCache(activity.getId());
        Map<String, Object> status1 = flowService.visit(activity.getId(), channel.getChannelCode(), null, false);
        assertEquals("CLAIM", status1.get("action"));
        assertEquals("公众号", status1.get("source"));

        // 停用渠道应立即失效读缓存
        flowService.setChannelEnabled(activity.getId(), channel.getId(), false);
        Map<String, Object> status2 = flowService.visit(activity.getId(), channel.getChannelCode(), null, false);
        assertEquals("UNAVAILABLE", status2.get("action"));

        // 恢复渠道后暂停活动也应立即失效读缓存
        flowService.setChannelEnabled(activity.getId(), channel.getId(), true);
        assertEquals("CLAIM", flowService.visit(activity.getId(), channel.getChannelCode(), null, false).get("action"));
        campaignService.pause(activity.getId());
        assertEquals("UNAVAILABLE", flowService.visit(activity.getId(), channel.getChannelCode(), null, false).get("action"));
    }

    @Test
    void draftPublicEntryDoesNotLeakCouponDetailsAndEndedActivityAllowsDirectClaim() {
        Shop shop = shop();
        asMerchant(shop);
        LocalDateTime now = LocalDateTime.now();
        OfferPlan template = templateService.create(shop.storeCode(), "草稿隔离券", IssuerScope.MERCHANT, ApplyScope.STOREWIDE, null, BenefitKind.AMOUNT_OFF, 10,
                now.minusDays(2), now.plusDays(3),
                "{\"maxClaimsPerUser\":2}", "{\"capAmount\":\"1.00\",\"validityPeriodHours\":24}");
        var draft = campaignService.create("未发布草稿", now.plusHours(1), now.plusHours(2), now.plusDays(1));
        campaignService.attachCoupon(draft.getId(), template.getId());
        flowService.place(draft.getId(), 5, 1, "draft-alloc", new BigDecimal("50.00"));
        flowService.openMain(draft.getId());

        // P2-5：草稿活动公开访问只返回不可用提示，不暴露券规则与库存
        Map<String, Object> draftView = flowService.visit(draft.getId(), null, "v-draft", false);
        assertEquals("UNAVAILABLE", draftView.get("action"));
        org.junit.jupiter.api.Assertions.assertNull(draftView.get("planId"));
        org.junit.jupiter.api.Assertions.assertNull(draftView.get("quantity"));

        // P2-6：活动自然结束后不再永久阻止该券的直接领取
        var ended = campaignService.create("已结束活动", now.minusDays(2), now.minusDays(1), now.plusSeconds(3));
        campaignService.attachCoupon(ended.getId(), template.getId());
        flowService.place(ended.getId(), 2, 1, "ended-alloc", new BigDecimal("50.00"));
        flowService.openMain(ended.getId());
        campaignService.publish(ended.getId());
        assertThrows(BizException.class, () -> campaignService.assertDirectClaimAllowed(template.getId()));
        // 将结束时间改到过去模拟活动自然结束，校验直接领取恢复允许
        ended.setEndTime(now.minusMinutes(1));
        activityMapper.updateById(ended);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> campaignService.assertDirectClaimAllowed(template.getId()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shortlinkClicksWithChannelCodeFeedIntoReviewFunnel() {
        Shop shop = shop();
        asMerchant(shop);
        LocalDateTime now = LocalDateTime.now();
        OfferPlan template = templateService.create(shop.storeCode(), "漏斗券", IssuerScope.MERCHANT, ApplyScope.STOREWIDE, null, BenefitKind.AMOUNT_OFF, 10,
                now.minusDays(1), now.plusDays(3),
                "{\"maxClaimsPerUser\":1}", "{\"capAmount\":\"1.00\",\"validityPeriodHours\":24}");
        var activity = campaignService.create("五级漏斗活动", now.minusHours(2), now.minusMinutes(5), now.plusDays(1));
        campaignService.attachCoupon(activity.getId(), template.getId());
        flowService.place(activity.getId(), 5, 1, "funnel-alloc", new BigDecimal("50.00"));
        CampaignChannel channel = flowService.addChannel(activity.getId(), "抖音", "dy-ch");
        campaignService.publish(activity.getId());
        Link link = linkMapper.selectById(channel.getLinkId());

        // 模拟 2 位访客通过抖音渠道短链跳转，1 位直接跳转
        redirectService.resolve(link.getShortUri(), "v-click-1", true, channel.getChannelCode());
        redirectService.resolve(link.getShortUri(), "v-click-2", true, channel.getChannelCode());
        redirectService.resolve(link.getShortUri(), "v-click-3", true, null);
        outboxPublisher.publishCycle();

        Map<String, Object> review = flowService.review(activity.getId());
        Map<String, Object> funnel = (Map<String, Object>) review.get("funnel");
        assertEquals(3L, funnel.get("clicks"));
        var dyRow = flowService.report(activity.getId()).stream()
                .filter(row -> channel.getId().equals(row.get("channelId"))).findFirst().orElseThrow();
        assertEquals(2L, dyRow.get("clicks"));
    }

    @Test
    void activityTypeEnforcesNewUserAndMemberEligibility() {
        Shop shop = shop();
        asMerchant(shop);
        LocalDateTime now = LocalDateTime.now();
        OfferPlan template = templateService.create(shop.storeCode(), "资格券", IssuerScope.MERCHANT, ApplyScope.STOREWIDE, null, BenefitKind.AMOUNT_OFF, 20,
                now.minusDays(1), now.plusDays(3),
                "{\"maxClaimsPerUser\":5}", "{\"capAmount\":\"1.00\",\"validityPeriodHours\":24}");

        // 1. 新客领取活动：新用户可领，已领过券的老用户被拦截
        var newUserAct = campaignService.create("新客专场", now.minusHours(2), now.minusMinutes(5), now.plusDays(1), "新客领取活动", new BigDecimal("50.00"));
        campaignService.attachCoupon(newUserAct.getId(), template.getId());
        flowService.place(newUserAct.getId(), 5, 2, "new-alloc", new BigDecimal("50.00"));
        flowService.openMain(newUserAct.getId());
        campaignService.publish(newUserAct.getId());
        String newCred = (String) flowService.visit(newUserAct.getId(), null, "v-new-1").get("credential");
        long userId = 6601L;
        assertEquals(true, flowService.personalState(newUserAct.getId(), userId).get("eligible"));
        flowService.claim(userId, newUserAct.getId(), template.getId(), "new-r1", newCred);
        assertEquals(false, flowService.personalState(newUserAct.getId(), userId).get("eligible"));
        assertThrows(BizException.class, () -> flowService.claim(userId, newUserAct.getId(), template.getId(), "new-r2", newCred));

        // 2. 会员专享活动：未核销用户被拦截
        var memberAct = campaignService.create("会员专场", now.minusHours(2), now.minusMinutes(5), now.plusDays(1), "会员专享活动", new BigDecimal("50.00"));
        campaignService.attachCoupon(memberAct.getId(), template.getId());
        flowService.place(memberAct.getId(), 5, 2, "mem-alloc", new BigDecimal("50.00"));
        flowService.openMain(memberAct.getId());
        campaignService.publish(memberAct.getId());
        String memCred = (String) flowService.visit(memberAct.getId(), null, "v-mem-1").get("credential");
        assertEquals(false, flowService.personalState(memberAct.getId(), userId).get("eligible"));
        assertThrows(BizException.class, () -> flowService.claim(userId, memberAct.getId(), template.getId(), "mem-r1", memCred));
    }

    @Test
    void autoPrepareUpcomingPreparesActivityWithoutUserSession() {
        Shop shop = shop();
        asMerchant(shop);
        LocalDateTime now = LocalDateTime.now();
        OfferPlan template = templateService.create(shop.storeCode(), "自动预热券", IssuerScope.MERCHANT, ApplyScope.STOREWIDE, null, BenefitKind.AMOUNT_OFF, 10,
                now.minusDays(1), now.plusDays(3),
                "{\"maxClaimsPerUser\":1}", "{\"capAmount\":\"1.00\",\"validityPeriodHours\":24}");
        var activity = campaignService.create("3分钟后开场", now.minusMinutes(10), now.plusMinutes(3), now.plusDays(1), "优惠券推广活动", new BigDecimal("50.00"));
        campaignService.attachCoupon(activity.getId(), template.getId());
        flowService.place(activity.getId(), 5, 1, "auto-prep-alloc", new BigDecimal("50.00"));
        flowService.openMain(activity.getId());
        campaignService.publish(activity.getId());

        // 清空用户会话，模拟后台定时任务无会话触发自动预热（P2-7）
        UserContext.clear();
        int count = preparationService.autoPrepareUpcoming();
        org.junit.jupiter.api.Assertions.assertTrue(count >= 1);
        org.junit.jupiter.api.Assertions.assertNull(UserContext.get());

        asMerchant(shop);
        var runs = operationStore.list(activity.getId(), "PREPARE");
        assertEquals(1, runs.size());
        assertEquals(false, runs.get(0).get("stale"));

        // 重复调度不会重复创建新预热记录
        UserContext.clear();
        preparationService.autoPrepareUpcoming();
        asMerchant(shop);
        assertEquals(1, operationStore.list(activity.getId(), "PREPARE").size());
    }

    @Test
    void recycleDueSkipsAlreadyFrozenActivitiesAndRetentionPurgesExpiredRecords() {
        Shop shop = shop();
        asMerchant(shop);
        LocalDateTime now = LocalDateTime.now();
        OfferPlan template = templateService.create(shop.storeCode(), "回收与留存券", IssuerScope.MERCHANT, ApplyScope.STOREWIDE, null, BenefitKind.AMOUNT_OFF, 10,
                now.minusDays(1), now.plusDays(3),
                "{\"maxClaimsPerUser\":1}", "{\"capAmount\":\"1.00\",\"validityPeriodHours\":24}");
        var activity = campaignService.create("到期回收测试", now.minusHours(2), now.minusHours(1), now.plusHours(1));
        campaignService.attachCoupon(activity.getId(), template.getId());
        flowService.place(activity.getId(), 5, 1, "rec-due-alloc", new BigDecimal("50.00"));
        flowService.openMain(activity.getId());
        var published = campaignService.publish(activity.getId());

        // 将结束时间改到过去，首次 recycleDue 应冻结该活动的开放投放，第二次调用应跳过已冻结活动（返回 0）
        published.setEndTime(now.minusMinutes(1));
        activityMapper.updateById(published);
        org.junit.jupiter.api.Assertions.assertTrue(campaignService.recycleDue() >= 1);
        assertEquals(0, campaignService.recycleDue());

        // 插入一条超过 100 天且未被引用的过期访问记录，验证 purgeExpired 安全清理
        CampaignVisit expiredVisit = new CampaignVisit();
        expiredVisit.setVisitTokenHash("expired-token-" + UUID.randomUUID());
        expiredVisit.setActivityId(activity.getId());
        expiredVisit.setVisitorId("v-expired");
        expiredVisit.setVisitedAt(now.minusDays(100));
        expiredVisit.setExpireAt(now.minusDays(100).plusMinutes(30));
        expiredVisit.setValidFlag(1);
        visitMapper.insert(expiredVisit);

        Map<String, Integer> purged = dataRetentionService.purgeExpired(now);
        org.junit.jupiter.api.Assertions.assertTrue(purged.get("visitsDeleted") >= 1);
        org.junit.jupiter.api.Assertions.assertNull(visitMapper.selectById(expiredVisit.getId()));
    }

    @Test
    void couponTemplateAndActivityPeriodStayConsistent() {
        Shop shop = shop();
        asMerchant(shop);
        LocalDateTime now = LocalDateTime.now();
        var activity = campaignService.create("时间边界校验", now.minusHours(1), now.plusHours(1), now.plusDays(1));
        OfferPlan lateStart = templateService.create(shop.storeCode(), "晚生效券", IssuerScope.MERCHANT, ApplyScope.STOREWIDE, null, BenefitKind.AMOUNT_OFF, 10,
                now.plusHours(2), now.plusDays(3),
                "{\"maxClaimsPerUser\":2}", "{\"capAmount\":\"1.00\",\"validityPeriodHours\":24}");
        assertThrows(BizException.class, () -> campaignService.attachCoupon(activity.getId(), lateStart.getId()));

        OfferPlan valid = templateService.create(shop.storeCode(), "合规券", IssuerScope.MERCHANT, ApplyScope.STOREWIDE, null, BenefitKind.AMOUNT_OFF, 10,
                now.minusHours(1), now.plusDays(3),
                "{\"maxClaimsPerUser\":2}", "{\"capAmount\":\"1.00\",\"validityPeriodHours\":24}");
        campaignService.attachCoupon(activity.getId(), valid.getId());
        flowService.place(activity.getId(), 5, 2, "period-alloc", new BigDecimal("50.00"));
        flowService.openMain(activity.getId());

        // 活动开场时间不能早于已绑定券模板的生效开始时间
        assertThrows(BizException.class, () -> campaignService.postpone(
                activity.getId(), now.minusHours(3), now.minusHours(2), now.plusDays(1)));

        // 已关联活动时，禁止将券模板有效期缩窄到活动区间内或将模板限领调低到单场限领以下
        assertThrows(BizException.class, () -> templateService.revise(
                valid.getId(), null, now.plusHours(2), now.plusDays(3), null, null));
        assertThrows(BizException.class, () -> templateService.revise(
                valid.getId(), null, now.minusHours(1), now.plusHours(12), null, null));
        assertThrows(BizException.class, () -> templateService.revise(
                valid.getId(), null, null, null, "{\"maxClaimsPerUser\":1}", null));

        campaignService.publish(activity.getId());
        assertThrows(BizException.class, () -> templateService.terminate(valid.getId()));

        campaignService.cancel(activity.getId());
        templateService.terminate(valid.getId());
    }

    @Test
    void listActivitiesSummaryMatchesSingleActivityStats() {
        Shop shop = shop();
        asMerchant(shop);
        LocalDateTime now = LocalDateTime.now();
        OfferPlan template = templateService.create(shop.storeCode(), "汇总券", IssuerScope.MERCHANT, ApplyScope.STOREWIDE, null, BenefitKind.AMOUNT_OFF, 10,
                now.minusHours(1), now.plusDays(3),
                "{\"maxClaimsPerUser\":1}", "{\"capAmount\":\"1.00\",\"validityPeriodHours\":24}");
        var first = campaignService.create("批量汇总一", now.minusHours(2), now.minusMinutes(5), now.plusDays(1));
        flowService.saveOffer(first.getId(), template.getId(), 5, 1, "sum-1", new BigDecimal("20.00"), null);
        flowService.openMain(first.getId());
        CampaignChannel ch = flowService.addChannel(first.getId(), "公众号", "sum-ch-1");
        campaignService.publish(first.getId());
        var entry = flowService.visit(first.getId(), ch.getChannelCode(), "sum-visitor");
        flowService.claim(88L, first.getId(), template.getId(), "sum-claim-1", String.valueOf(entry.get("credential")));

        var second = campaignService.create("批量汇总二", now.minusHours(1), now.plusHours(1), now.plusDays(1));
        flowService.openMain(second.getId());

        var rows = flowService.listActivitiesSummary();
        assertEquals(2, rows.size());
        Map<String, Object> firstRow = rows.stream().filter(r -> first.getId().equals(r.get("id"))).findFirst().orElseThrow();
        Map<String, Object> single = campaignService.present(campaignService.requireOwned(first.getId()));
        assertEquals(single.get("checksPassed"), firstRow.get("checksPassed"));
        assertEquals(single.get("checksTotal"), firstRow.get("checksTotal"));
        assertEquals(single.get("couponName"), firstRow.get("couponName"));
        assertEquals(1, ((Number) firstRow.get("channelCount")).intValue());
        assertEquals(1L, ((Number) firstRow.get("enabledChannels")).longValue());
        assertEquals(2L, ((Number) firstRow.get("entryCount")).longValue());
        assertEquals(1L, ((Number) firstRow.get("visits")).longValue());
        assertEquals(1L, ((Number) firstRow.get("claims")).longValue());
        assertEquals(0L, ((Number) firstRow.get("reservations")).longValue());
    }

    @Test
    void activityPublishesAndClaimsWithoutMainShortLinkAndBlocksDirectLinkTampering() {
        Shop shop = shop();
        asMerchant(shop);
        LocalDateTime now = LocalDateTime.now();
        OfferPlan template = templateService.create(shop.storeCode(), "直达解耦券", IssuerScope.MERCHANT, ApplyScope.STOREWIDE, null, BenefitKind.AMOUNT_OFF, 10,
                now.minusDays(1), now.plusDays(3),
                "{\"maxClaimsPerUser\":1}", "{\"capAmount\":\"2.00\",\"validityPeriodHours\":24}");
        var activity = campaignService.create("直达优先活动", now.minusHours(2), now.minusMinutes(5), now.plusDays(1));
        flowService.saveOffer(activity.getId(), template.getId(), 5, 1, "direct-only-offer", new BigDecimal("20.00"), null);

        // 不生成主短链即可发布，且主短链检查为非阻断提示项
        var checks = campaignService.checks(activity.getId());
        var linkCheck = checks.stream().filter(row -> "main-link".equals(row.get("code"))).findFirst().orElseThrow();
        assertEquals(false, linkCheck.get("blocking"));
        assertEquals(false, linkCheck.get("passed"));
        campaignService.publish(activity.getId());

        // 未生成主短链时直接访问落地页与领券可用
        Map<String, Object> directVisit = flowService.visit(activity.getId(), null, "direct-visitor-1");
        assertEquals("CLAIM", directVisit.get("action"));
        assertEquals("DIRECT", directVisit.get("source"));
        var voucher = flowService.claim(901L, activity.getId(), template.getId(), "direct-claim-1", String.valueOf(directVisit.get("credential")));
        org.junit.jupiter.api.Assertions.assertNotNull(voucher.getId());

        // 生成并停用主短链后：通过短链入口受阻，但活动直达页仍可正常访问和领券，且禁止普通短链接口篡改或回收主链
        Map<String, Object> main = flowService.openMain(activity.getId());
        long linkId = ((Number) main.get("id")).longValue();
        String shortUri = String.valueOf(main.get("shortUri"));
        assertThrows(BizException.class, () -> linkDirectoryService.updateLink(linkId, "https://evil.example", null, null));
        assertThrows(BizException.class, () -> linkDirectoryService.recycle(linkId));

        campaignService.setMainEnabled(activity.getId(), false);
        assertEquals("UNAVAILABLE", flowService.entry(shortUri, null, "short-blocked").get("action"));
        Map<String, Object> stillOpen = flowService.visit(activity.getId(), null, "direct-visitor-2");
        assertEquals("CLAIM", stillOpen.get("action"));
        flowService.claim(902L, activity.getId(), template.getId(), "direct-claim-2", String.valueOf(stillOpen.get("credential")));
    }

    private Shop shop() {
        IamMerchant merchant = new IamMerchant();
        merchant.setName("活动商家" + UUID.randomUUID());
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
