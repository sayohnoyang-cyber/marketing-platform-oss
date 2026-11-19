package com.sayohno.marketing.flow;

import com.sayohno.marketing.campaign.dao.entity.CampaignChannel;
import com.sayohno.marketing.campaign.service.CampaignFlowService;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestIdentityConfiguration.class, MemoryStockConfiguration.class})
class AllocationFlowTest {
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
    @Autowired
    private LinkMapper linkMapper;

    @AfterEach
    void clear() {
        UserContext.clear();
    }

    @Test
    void twoActivitiesCannotExceedTemplateAndLimitsStaySeparate() throws Exception {
        Shop shop = shop();
        asMerchant(shop);
        LocalDateTime now = LocalDateTime.now();
        OfferPlan template = template(shop, 10, 2);
        var first = campaignService.create("一场", now.plusHours(1), now.plusHours(2), now.plusDays(2));
        var second = campaignService.create("二场", now.plusHours(1), now.plusHours(2), now.plusDays(2));
        campaignService.attachCoupon(first.getId(), template.getId());
        campaignService.attachCoupon(second.getId(), template.getId());
        flowService.place(first.getId(), 6, 1, "a-6", new BigDecimal("100.00"));
        assertThrows(BizException.class, () -> flowService.place(second.getId(), 6, 1, "b-6", new BigDecimal("100.00")));
        flowService.place(second.getId(), 3, 1, "b-3", new BigDecimal("100.00"));
        OfferPlan fresh = templateMapper.selectById(template.getId());
        assertEquals(10, fresh.getIssuedTotal());
        assertEquals(1, fresh.getInventoryQty());
        assertEquals(9, allocationService.allocatedQuantity(template.getId()));

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        OfferPlan raced = template(shop, 10, 1);
        var left = campaignService.create("并发甲", now.plusHours(1), now.plusHours(3), now.plusDays(2));
        var right = campaignService.create("并发乙", now.plusHours(1), now.plusHours(3), now.plusDays(2));
        campaignService.attachCoupon(left.getId(), raced.getId());
        campaignService.attachCoupon(right.getId(), raced.getId());
        Future<?> one = pool.submit(() -> placeRace(shop, left.getId(), "race-a", latch, wins));
        Future<?> two = pool.submit(() -> placeRace(shop, right.getId(), "race-b", latch, wins));
        latch.countDown();
        one.get();
        two.get();
        pool.shutdown();
        assertEquals(1, wins.get());
        assertTrue(templateMapper.selectById(raced.getId()).getInventoryQty() >= 4);

        CampaignChannel channelA = flowService.addChannel(first.getId(), "微信", "wx");
        CampaignChannel channelB = flowService.addChannel(first.getId(), "短信", "sms");
        assertEquals(channelA.getLinkId(), channelB.getLinkId());
        flowService.addChannel(second.getId(), "直接旁路", "direct-b");
        campaignService.publish(first.getId());
        campaignService.publish(second.getId());
        campaignService.postpone(first.getId(), now.minusHours(2), now.minusMinutes(1), now.plusDays(1));
        campaignService.postpone(second.getId(), now.minusHours(2), now.minusMinutes(1), now.plusDays(1));
        Link link = linkMapper.selectById(channelA.getLinkId());
        Map<String, Object> viaA = flowService.entry(link.getShortUri(), channelA.getChannelCode(), "v1");
        Map<String, Object> viaB = flowService.entry(link.getShortUri(), channelB.getChannelCode(), "v1");
        Map<String, Object> direct = flowService.entry(link.getShortUri(), null, "v1");
        assertEquals("CLAIM", viaA.get("action"));
        assertEquals("DIRECT", direct.get("source"));
        var claimed = flowService.claim(8, first.getId(), template.getId(), "same-req", (String) viaA.get("credential"));
        var repeated = flowService.claim(8, first.getId(), template.getId(), "same-req", (String) viaA.get("credential"));
        assertEquals(claimed.getId(), repeated.getId());
        assertThrows(BizException.class, () -> flowService.claim(8, first.getId(), template.getId(), "another", (String) viaB.get("credential")));
        var otherActivity = flowService.entry(linkMapper.selectById(flowService.channels(second.getId()).get(0).getLinkId()).getShortUri(),
                flowService.channels(second.getId()).get(0).getChannelCode(), "v2");
        var other = flowService.claim(8, second.getId(), template.getId(), "same-req", (String) otherActivity.get("credential"));
        assertTrue(!other.getId().equals(claimed.getId()));

        campaignService.setMainEnabled(first.getId(), false);
        assertEquals("UNAVAILABLE", flowService.entry(link.getShortUri(), null, "v3").get("action"));
        campaignService.setMainEnabled(first.getId(), true);
        flowService.setChannelEnabled(first.getId(), channelA.getId(), false);
        assertEquals("UNAVAILABLE", flowService.entry(link.getShortUri(), channelA.getChannelCode(), "v4").get("action"));
        assertEquals("CLAIM", flowService.entry(link.getShortUri(), null, "v4").get("action"));

        assertThrows(BizException.class, () -> flowService.place(first.getId(), 5, 1, "shrink", null));
        flowService.place(first.getId(), 7, 1, "append", null);
        StockDecision decision = stockGateway.tryAcquireAllocation(9, template.getId(), 3, 1, 1, 1, 60, 9001);
        if (decision.ok()) {
            assertEquals(1, stockGateway.compensateAllocation(9, template.getId(), 3, decision.claimSeq(), decision.allocCount(), 9001));
            assertEquals(0, stockGateway.compensateAllocation(9, template.getId(), 3, decision.claimSeq(), decision.allocCount(), 9001));
        }

        long inventoryId = campaignService.coupons(first.getId()).get(0).getInventoryId();
        Shop otherShop = shop();
        asMerchant(otherShop);
        assertThrows(BizException.class, () -> campaignService.requireOwned(first.getId()));
        assertThrows(BizException.class, () -> allocationService.requireOwned(inventoryId));
    }

    @Test
    void outOfOrderCompensationRestoresQuotaAndAvoidsClaimSeqConflict() {
        Shop shop = shop();
        asMerchant(shop);
        LocalDateTime now = LocalDateTime.now();
        OfferPlan template = template(shop, 10, 2);
        var activity = campaignService.create("乱序回补测试", now.plusHours(1), now.plusHours(2), now.plusDays(2));
        campaignService.attachCoupon(activity.getId(), template.getId());
        var offer = flowService.place(activity.getId(), 5, 2, "oo-5", new BigDecimal("100.00"));
        long inventoryId = offer.getInventoryId();
        flowService.openMain(activity.getId());
        campaignService.publish(activity.getId());
        campaignService.postpone(activity.getId(), now.minusHours(2), now.minusMinutes(1), now.plusDays(1));
        Link link = linkMapper.selectById(campaignService.requireOwned(activity.getId()).getMainLinkId());
        Map<String, Object> entry = flowService.entry(link.getShortUri(), null, "v-oo");
        String credential = (String) entry.get("credential");

        long userId = 8801L;
        // 模拟 A(seq=1) 与 B(seq=2) 并发预扣，B 先落库、A 乱序回补
        StockDecision a = stockGateway.tryAcquireAllocation(inventoryId, template.getId(), userId, 2, 2, 5, 3600, 91001L);
        assertTrue(a.ok());
        assertEquals(1, a.claimSeq());
        var bVoucher = flowService.claim(userId, activity.getId(), template.getId(), "req-b", credential);
        assertEquals(1, bVoucher.getClaimSeq());
        // 此时 Redis 计数为 2，A 的序号为 1，乱序回补仍应将限领计数减回 1
        assertEquals(1, stockGateway.compensateAllocation(inventoryId, template.getId(), userId, a.claimSeq(), a.allocCount(), 91001L));
        // 随后用户再次领券 C，不应被误判超限，也不应触发 claim_seq 唯一键冲突
        var cVoucher = flowService.claim(userId, activity.getId(), template.getId(), "req-c", credential);
        assertEquals(2, cVoucher.getClaimSeq());
        assertThrows(BizException.class, () -> flowService.claim(userId, activity.getId(), template.getId(), "req-d", credential));
    }

    private void placeRace(Shop shop, long activityId, String key, CountDownLatch latch, AtomicInteger wins) {
        try {
            latch.await();
            asMerchant(shop);
            flowService.place(activityId, 6, 1, key, new BigDecimal("100.00"));
            wins.incrementAndGet();
        } catch (BizException ignored) {
            // 另一场已经拿走库存。
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } finally {
            UserContext.clear();
        }
    }

    private OfferPlan template(Shop shop, int stock, int limit) {
        LocalDateTime now = LocalDateTime.now();
        return templateService.create(shop.storeCode(), "投放券" + UUID.randomUUID(), IssuerScope.MERCHANT, ApplyScope.STOREWIDE, null, BenefitKind.AMOUNT_OFF, stock,
                now.minusDays(1), now.plusDays(3),
                "{\"maxClaimsPerUser\":" + limit + "}",
                "{\"capAmount\":\"1.00\",\"validityPeriodHours\":24}");
    }

    private Shop shop() {
        IamMerchant merchant = new IamMerchant();
        merchant.setName("投放商家" + UUID.randomUUID());
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
