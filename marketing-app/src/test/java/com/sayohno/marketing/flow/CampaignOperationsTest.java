package com.sayohno.marketing.flow;

import com.sayohno.marketing.campaign.dao.entity.CampaignOperation;
import com.sayohno.marketing.campaign.dao.mapper.CampaignOperationMapper;
import com.sayohno.marketing.campaign.service.*;
import com.sayohno.marketing.campaign.web.dto.*;
import com.sayohno.marketing.common.context.*;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.coupon.enums.*;
import com.sayohno.marketing.coupon.service.OfferPlanService;
import com.sayohno.marketing.identity.dao.entity.*;
import com.sayohno.marketing.identity.dao.mapper.*;
import com.sayohno.marketing.shortlink.service.*;
import com.sayohno.marketing.shortlink.dao.entity.LinkAccessLog;
import com.sayohno.marketing.shortlink.dao.mapper.LinkAccessLogMapper;
import com.sayohno.marketing.support.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestIdentityConfiguration.class, MemoryStockConfiguration.class})
class CampaignOperationsTest {
    @Autowired CampaignService campaigns;
    @Autowired CampaignFlowService flow;
    @Autowired OfferPlanService templates;
    @Autowired LinkDirectoryService links;
    @Autowired LinkStatisticsService statistics;
    @Autowired LinkAccessLogMapper logs;
    @Autowired CampaignForecastService forecasts;
    @Autowired CampaignPreparationService preparation;
    @Autowired CampaignOperationStore store;
    @Autowired CampaignOperationMapper operations;
    @Autowired CapacityBaselineService capacity;
    @Autowired CampaignRateLimitService limits;
    @Autowired IamMerchantMapper merchants;
    @Autowired IamShopMapper shops;
    @Autowired com.sayohno.marketing.coupon.service.VoucherRedemptionService settlements;
    @Autowired com.sayohno.marketing.campaign.mq.CampaignConversionHandler conversionHandler;
    @Autowired com.sayohno.marketing.common.outbox.OutboxEventMapper outbox;

    @AfterEach void clear() { UserContext.clear(); }

    @Test void visitorsAreDeduplicatedPerSourceAndGloballyAndBotsAreExcluded() {
        long id = activity();
        var channel = flow.addChannel(id, "微信", "wx-create", "wx");
        String code = String.valueOf(flow.mainLink(id).get("shortUri"));
        flow.entry(code, null, "same-browser");
        flow.entry(code, null, "same-browser");
        flow.entry(code, channel.getChannelCode(), "same-browser");
        var bot = flow.entry(code, channel.getChannelCode(), "bot-browser", false);
        assertFalse(bot.containsKey("credential"));
        flow.entry(code, "unknown", "invalid-browser");
        assertEquals(1, flow.effectiveVisitors(id));
        assertEquals(1L, flow.report(id).stream().filter(row -> row.get("kind").equals("DIRECT")).findFirst().orElseThrow().get("visits"));
        assertEquals(1L, flow.report(id).stream().filter(row -> row.get("kind").equals("CHANNEL")).findFirst().orElseThrow().get("visits"));
    }

    @Test void forecastIsDurableIdempotentTenantScopedAndInvalidatesAfterConfigurationChanges() {
        long id = activity();
        var visit = flow.visit(id, null, "forecast-browser");
        flow.reserve(91, id, String.valueOf(visit.get("credential")));
        var baseline = baseline(100);
        var request = new ForecastRequest("forecast-save", Map.of("direct", 50d), 100, 2, "normal", 10, Long.valueOf(baseline.get("id").toString()));
        var result = forecasts.save(id, request);
        var repeat = forecasts.save(id, request);
        assertEquals(result.get("id"), repeat.get("id"));
        var data = store.decode(store.require(id, Long.parseLong(result.get("id").toString()), "FORECAST"));
        assertEquals(1d, ((Number) data.get("arrivals")).doubleValue());
        assertEquals(80, ((Number) data.get("safeCapacity")).intValue());
        assertEquals("1", String.valueOf(data.get("totalVisits")));
        assertNotNull(data.get("visitToReservationRate"));
        assertThrows(BizException.class, () -> forecasts.save(id, new ForecastRequest("forecast-save", Map.of("direct", 60d), 100, 2, "normal", 10, null)));
        assertThrows(BizException.class, () -> limits.apply(id, new ApplyLimitRequest("no-redis", Long.parseLong(result.get("id").toString()))));
        flow.addChannel(id, "社群", "add-sq", "sq");
        assertEquals(true, store.list(id, "FORECAST").get(0).get("stale"));
        assertThrows(BizException.class, () -> forecasts.save(id, request));
        owner();
        assertThrows(BizException.class, () -> store.list(id, "FORECAST"));
        assertThrows(BizException.class, () -> capacity.requireUsable(Long.valueOf(baseline.get("id").toString())));
    }

    @Test void preparationPersistsRealPartialResultsNeverResetsStockAndFencesOldExecutors() {
        long id = activity();
        var before = campaigns.present(campaigns.requireOwned(id));
        var result = preparation.run(id, "prepare-once");
        assertEquals("PARTIAL", result.get("status"));
        assertEquals(result.get("id"), preparation.run(id, "prepare-once").get("id"));
        var run = store.require(id, Long.parseLong(result.get("id").toString()), "PREPARE");
        var data = store.decode(run);
        assertEquals(6, ((List<?>) data.get("tasks")).size());
        assertEquals("STANDARD", data.get("warmLevel"));
        assertFalse(((List<?>) data.get("recommendations")).isEmpty());
        assertEquals(before.get("quantity"), campaigns.present(campaigns.requireOwned(id)).get("quantity"));
        assertEquals(before.get("claimed"), campaigns.present(campaigns.requireOwned(id)).get("claimed"));
        // Simulate a crashed executor whose lease has expired.
        operations.update(null, Wrappers.<CampaignOperation>lambdaUpdate().eq(CampaignOperation::getId, run.getId())
                .set(CampaignOperation::getStatus, "RUNNING").set(CampaignOperation::getUpdateTime, LocalDateTime.now().minusMinutes(1)));
        assertEquals("PARTIAL", preparation.run(id, "prepare-once").get("status"));
        assertThrows(BizException.class, () -> store.update(run, "SUCCEEDED", Map.of("forged", true)));
        assertFalse(store.decode(store.require(id, run.getId(), "PREPARE")).containsKey("forged"));
    }

    @Test void customLinksAndChannelsKeepIdempotencyAndStatisticsBelongToTheirMerchant() {
        owner();
        var group = links.createGroup("测试分组");
        String suffix = "qa" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        var link = links.createLink(group.getGid(), "https://example.com", null, "custom", null, null, "会员中心", suffix);
        assertEquals("会员中心", link.getName());
        assertEquals(suffix, links.findByShortUri(suffix).getShortUri());
        assertEquals(link.getId(), links.createLink(group.getGid(), "https://example.com", null, "custom", null, null, "会员中心", suffix).getId());
        assertThrows(BizException.class, () -> links.createLink(group.getGid(), "https://example.org", null, "other", null, null, "另一条", suffix));
        assertThrows(BizException.class, () -> links.createLink(group.getGid(), "https://example.com", null, "invalid", null, null, "券", "a/"));
        for (String visitor : List.of("same", "same", "other")) {
            LinkAccessLog log = new LinkAccessLog(); log.setEventId(UUID.randomUUID().toString()); log.setLinkId(link.getId());
            log.setVisitorId(visitor); log.setVisitTime(LocalDateTime.now()); logs.insert(log);
        }
        @SuppressWarnings("unchecked") var rows = (List<Map<String,Object>>) statistics.summary().get("links");
        var stat = rows.stream().filter(row -> row.get("id").toString().equals(link.getId().toString())).findFirst().orElseThrow();
        assertEquals(2, ((Number) stat.get("visits")).intValue());
        owner();
        @SuppressWarnings("unchecked") var other = (List<Map<String,Object>>) statistics.summary().get("links");
        assertTrue(other.stream().noneMatch(row -> row.get("id").toString().equals(link.getId().toString())));
        long id = activity();
        var ch = flow.addChannel(id, "社群", "same-key", "sq");
        assertEquals(ch.getId(), flow.addChannel(id, "社群", "same-key", "sq").getId());
        assertThrows(BizException.class, () -> flow.addChannel(id, "其他", "other-key", "sq"));
    }

    @Test void capacityRejectsImpossibleOrStaleMeasurements() {
        owner(); LocalDateTime now = LocalDateTime.now();
        assertThrows(BizException.class, () -> capacity.create(new CapacityRequest("local-single", "不合法样本", 1000, 20, 100, BigDecimal.ZERO, 60, 1000, now, now.plusDays(1))));
        assertThrows(BizException.class, () -> capacity.create(new CapacityRequest("local-single", "未来报告", 100, 20, 100, BigDecimal.ZERO, 60, 6000, now.plusDays(1), now.plusDays(2))));
    }

    @Test void settlementConversionUsesAnIndependentOutboxNamespaceAndReplaysOnce() {
        long id = activity();
        var now = LocalDateTime.now();
        campaigns.postpone(id, now.minusHours(2), now.minusMinutes(1), now.plusDays(1));
        var visit = flow.visit(id,null,"settlement-browser");
        long templateId = ((Number) campaigns.present(campaigns.requireOwned(id)).get("planId")).longValue();
        var coupon = flow.claim(91,id,templateId,"settlement-claim",visit.get("credential").toString());
        settlements.lock(91,coupon.getId(),"qa-order",new BigDecimal("10"),null);
        var settlement = settlements.pay(91,coupon.getId(),"qa-order");
        var event = outbox.selectOne(Wrappers.<com.sayohno.marketing.common.outbox.OutboxEvent>lambdaQuery()
                .eq(com.sayohno.marketing.common.outbox.OutboxEvent::getEventId,"redeem:"+settlement.getId()));
        conversionHandler.handle(event); conversionHandler.handle(event);
        assertEquals(1L,flow.report(id).stream().filter(row -> row.get("kind").equals("DIRECT")).findFirst().orElseThrow().get("redeems"));
        assertEquals(1,outbox.selectCount(Wrappers.<com.sayohno.marketing.common.outbox.OutboxEvent>lambdaQuery()
                .eq(com.sayohno.marketing.common.outbox.OutboxEvent::getEventId,"campaign:redeem:"+settlement.getId())));
    }

    @Test void openingPlanTracksReservationsCapacityAndSnapshotFreshness() {
        long id = activity();
        var empty = forecasts.plan(id);
        assertEquals("NO_SAMPLE", empty.get("capacityStatus"));
        assertNull(empty.get("peakQps"));
        assertEquals("COLLECT_RESERVATIONS", empty.get("nextAction"));
        assertTrue(store.list(id, "FORECAST").isEmpty());
        flow.reserve(801, id, String.valueOf(flow.visit(id, null, "plan-first").get("credential")));
        var preview = forecasts.plan(id);
        assertEquals(1L, ((Number) preview.get("reservationCount")).longValue());
        assertEquals("UNVERIFIED", preview.get("capacityStatus"));
        assertEquals("SAVE_FORECAST", preview.get("nextAction"));
        var report = baseline(10);
        forecasts.save(id, new ForecastRequest("plan-saved", Map.of("direct", 100d), 0, 2, "normal", 100,
                Long.valueOf(report.get("id").toString())));
        var over = forecasts.plan(id);
        assertEquals("OVER_CAPACITY", over.get("capacityStatus"));
        assertEquals("REVIEW_CAPACITY", over.get("nextAction"));
        assertEquals(false, over.get("forecastNeedsRefresh"));
        flow.reserve(802, id, String.valueOf(flow.visit(id, null, "plan-second").get("credential")));
        var changed = forecasts.plan(id);
        assertEquals(2L, ((Number) changed.get("reservationCount")).longValue());
        assertEquals(true, changed.get("reservationsChanged"));
        assertEquals(true, changed.get("forecastNeedsRefresh"));
        assertEquals(1, store.list(id, "FORECAST").size());
        forecasts.save(id, new ForecastRequest("plan-within", Map.of("direct", 100d), 0, 2, "normal", 0,
                Long.valueOf(report.get("id").toString())));
        var within = forecasts.plan(id);
        assertEquals("WITHIN_CAPACITY", within.get("capacityStatus"));
        assertEquals("RUN_PREPARATION", within.get("nextAction"));
        preparation.run(id, "plan-prepare");
        var afterPrep = forecasts.plan(id);
        assertEquals(true, afterPrep.get("prepared"));
        assertEquals("MONITOR_OPENING", afterPrep.get("nextAction"));
        campaigns.setMainEnabled(id, false);
        var disabled = forecasts.plan(id);
        assertEquals("NO_ACTIVE_ENTRY", disabled.get("capacityStatus"));
        assertEquals(0L, ((Number) disabled.get("eligibleReservations")).longValue());
        assertNull(disabled.get("peakQps"));
        // 尚无预约但有短链有效访问时，依据访问量推算潜在开场压力参与容量判断
        long visitOnlyId = activity();
        var visitMain = flow.mainLink(visitOnlyId);
        flow.entry(String.valueOf(visitMain.get("shortUri")), null, "v-only-1");
        var withVisits = forecasts.plan(visitOnlyId);
        assertEquals(1L, ((Number) withVisits.get("totalVisits")).longValue());
        assertNotNull(withVisits.get("peakQps"));
        assertEquals("UNVERIFIED", withVisits.get("capacityStatus"));
        owner();
        assertThrows(BizException.class, () -> forecasts.plan(id));
    }

    private Map<String,Object> baseline(int qps) {
        LocalDateTime now = LocalDateTime.now();
        return capacity.create(new CapacityRequest("local-single", "合成测试报告", qps, 20, 100, BigDecimal.ZERO, 60, Math.max(1000,qps*60), now, now.plusDays(1)));
    }
    private long activity() {
        owner(); LocalDateTime now = LocalDateTime.now();
        var template = templates.create(UserContext.require().getStoreCode(), "活动券", IssuerScope.MERCHANT, ApplyScope.STOREWIDE, null, BenefitKind.AMOUNT_OFF_OVER, 100,
                now.minusDays(1), now.plusDays(3), "{\"maxClaimsPerUser\":1}", "{\"capAmount\":\"1.00\",\"validityPeriodHours\":24}");
        var activity = campaigns.create("运营测试活动", now.minusHours(1), now.plusHours(2), now.plusDays(1));
        flow.saveOffer(activity.getId(), template.getId(), 20, 1, "initial-offer", new BigDecimal("20"), null);
        flow.openMain(activity.getId()); campaigns.publish(activity.getId()); return activity.getId();
    }
    private void owner() {
        var merchant = new IamMerchant(); merchant.setName("运营测试" + UUID.randomUUID()); merchant.setStatus(1); merchants.insert(merchant);
        var shop = new IamShop(); shop.setMerchantId(merchant.getId()); shop.setName("测试店"); shop.setStoreCode("S"+UUID.randomUUID().toString().substring(0,8)); shop.setStatus(1); shops.insert(shop);
        UserContext.set(CurrentUser.builder().userId(merchant.getId()).username("qa").merchantId(merchant.getId()).storeCode(shop.getStoreCode()).roles(Set.of(UserRole.OWNER,UserRole.USER)).build());
    }
}
