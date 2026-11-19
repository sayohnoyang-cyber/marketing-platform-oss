package com.sayohno.marketing.flow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sayohno.marketing.campaign.dao.entity.CampaignChannel;
import com.sayohno.marketing.campaign.dao.entity.CampaignVisit;
import com.sayohno.marketing.campaign.dao.mapper.CampaignVisitMapper;
import com.sayohno.marketing.campaign.service.CampaignFlowService;
import com.sayohno.marketing.campaign.service.CampaignService;
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
    private OfferPlanService templateService;
    @Autowired
    private IamMerchantMapper merchantMapper;
    @Autowired
    private IamShopMapper shopMapper;
    @Autowired
    private LinkMapper linkMapper;
    @Autowired
    private CampaignVisitMapper visitMapper;
    @Autowired
    private VoucherClaimService claimService;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private OutboxPublisher outboxPublisher;
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
