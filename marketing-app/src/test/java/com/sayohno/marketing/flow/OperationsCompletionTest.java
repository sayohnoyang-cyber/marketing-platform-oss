package com.sayohno.marketing.flow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sayohno.marketing.campaign.dao.entity.*;
import com.sayohno.marketing.campaign.dao.mapper.*;
import com.sayohno.marketing.campaign.service.*;
import com.sayohno.marketing.common.context.*;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.common.outbox.*;
import com.sayohno.marketing.coupon.enums.*;
import com.sayohno.marketing.coupon.service.*;
import com.sayohno.marketing.identity.dao.entity.IamMerchant;
import com.sayohno.marketing.identity.dao.mapper.IamMerchantMapper;
import com.sayohno.marketing.support.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestIdentityConfiguration.class, MemoryStockConfiguration.class})
class OperationsCompletionTest {
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired CampaignService campaigns;
    @Autowired CampaignFlowService flow;
    @Autowired CampaignCalibrationService calibration;
    @Autowired CampaignMessageService messages;
    @Autowired CampaignActivityMapper activities;
    @Autowired CampaignChannelMapper channels;
    @Autowired CampaignReservationMapper reservations;
    @Autowired CampaignVisitMapper visits;
    @Autowired IamMerchantMapper merchants;
    @Autowired OfferPlanService templates;
    @Autowired VoucherRedemptionService settlements;
    @Autowired OutboxPublisher publisher;
    @Autowired OutboxEventMapper events;
    @Autowired OutboxStore outbox;
    @Autowired com.sayohno.marketing.campaign.mq.CampaignConversionHandler conversions;

    @AfterEach void clear() { UserContext.clear(); }

    @Test void historicalAttendanceDeduplicatesUsersMatchesChannelNamesAndNeverCrossesWorkspaces() {
        owner(); var original = UserContext.require();
        LocalDateTime now = LocalDateTime.now().withNano(0), start = now.minusDays(2);
        var past = historical(start, "优惠券推广活动");
        var pastChannel = channel(past.getId(), "社群");
        for (long uid = 1; uid <= 20; uid++) {
            reserve(past.getId(), pastChannel.getId(), uid, start.minusMinutes(30), false);
            if (uid <= 8) {
                visit(past.getId(), uid, "person-" + uid, start.plusSeconds(1), true);
                visit(past.getId(), uid, "person-" + uid, start.plusSeconds(2), true);
            }
        }
        // Anonymous arrival is linked to the authenticated pre-opening reservation browser.
        visit(past.getId(), 9L, "same-browser", start.minusMinutes(31), true);
        visit(past.getId(), null, "same-browser", start.plusSeconds(3), true);
        visit(past.getId(), 10L, "outside-window", start.plusSeconds(10), true);
        visit(past.getId(), 11L, "invalid-visit", start.plusSeconds(3), false);
        reserve(past.getId(), pastChannel.getId(), 21, start.minusMinutes(30), true);
        visit(past.getId(), 21L, "cancelled", start.plusSeconds(1), true);
        var differentType = historical(start.plusHours(1), "会员专享活动");
        reserve(differentType.getId(), null, 90, start, false);
        var target = campaigns.create("下一场", now, now.plusDays(1), now.plusDays(2));
        var targetChannel = channel(target.getId(), "社群");
        owner(); var foreign = historical(start, "优惠券推广活动");
        for (long uid = 1; uid <= 30; uid++) {
            reserve(foreign.getId(), null, uid, start.minusMinutes(30), false);
            visit(foreign.getId(), uid, "foreign-" + uid, start.plusSeconds(1), true);
        }
        assertThrows(BizException.class, () -> calibration.recommend(target.getId()));
        UserContext.set(original);
        var result = calibration.recommend(target.getId());
        assertEquals(20L, result.get("sampleCount"));
        assertEquals(9L, result.get("arrivedCount"));
        @SuppressWarnings("unchecked") var rows = (List<Map<String,Object>>) result.get("rows");
        var channelRate = rows.stream().filter(row -> row.get("key").equals(targetChannel.getId().toString())).findFirst().orElseThrow();
        assertEquals(45.0, channelRate.get("rate")); assertEquals("CHANNEL_HISTORY", channelRate.get("source"));
        assertEquals("WORKSPACE_HISTORY", rows.get(0).get("source"));
        var emptyType = campaigns.create("无历史类型", now, now.plusDays(1), now.plusDays(2), "新客领取活动", null);
        @SuppressWarnings("unchecked") var defaults = (List<Map<String,Object>>) calibration.recommend(emptyType.getId()).get("rows");
        assertEquals(40.0, defaults.get(0).get("rate")); assertEquals(false, defaults.get(0).get("usable"));
    }

    @Test void refundAmountCanBeCompletedWithoutRepeatingConversionAndMessageReceiptsAreScoped() {
        owner(); LocalDateTime now = LocalDateTime.now();
        var template = templates.create(null, "满减券", IssuerScope.MERCHANT, ApplyScope.STOREWIDE, null, BenefitKind.AMOUNT_OFF_OVER, 10, now.minusDays(1), now.plusDays(3),
                "{\"maxClaimsPerUser\":1}", "{\"minSpend\":\"100\",\"capAmount\":\"30.00\",\"validityPeriodHours\":24}");
        var activity = campaigns.create("退款活动", now.minusHours(2), now.minusHours(1), now.plusDays(1));
        flow.saveOffer(activity.getId(), template.getId(), 5, 1, "stock", new BigDecimal("150"), null);
        flow.openMain(activity.getId()); campaigns.publish(activity.getId());
        var entry = flow.visit(activity.getId(), null, "refund-browser");
        var coupon = flow.claim(91, activity.getId(), template.getId(), "refund-claim", entry.get("credential").toString());
        var locked = settlements.lock(91, coupon.getId(), "order", new BigDecimal("200.00"), null);
        assertEquals(new BigDecimal("200.00"), locked.getOrderAmount());
        assertThrows(BizException.class, () -> settlements.lock(91, coupon.getId(), "order", new BigDecimal("201.00"), null));
        assertThrows(BizException.class, () -> settlements.history(92, coupon.getId()));
        settlements.pay(91, coupon.getId(), "order");
        assertThrows(BizException.class, () -> settlements.refund(91, coupon.getId(), "order", new BigDecimal("50.00")));
        var refunded = settlements.refund(91, coupon.getId(), "order");
        var event = events.selectOne(Wrappers.<OutboxEvent>lambdaQuery().eq(OutboxEvent::getEventId, "refund:"+refunded.getId()));
        conversions.handle(event); conversions.handle(event);
        var partial = flow.review(activity.getId());
        assertNull(partial.get("refundAmount")); assertEquals(1L, partial.get("unreportedRefunds"));
        settlements.refund(91, coupon.getId(), "order", new BigDecimal("170.00"));
        settlements.refund(91, coupon.getId(), "order", new BigDecimal("170.00"));
        var report = flow.review(activity.getId());
        assertEquals(0, new BigDecimal("170.00").compareTo((BigDecimal) report.get("refundAmount")));
        assertEquals(0L, report.get("unreportedRefunds"));
        assertEquals(1L, ((Number) flow.report(activity.getId()).get(0).get("refunds")).longValue());
        for (int i = 0; i < 3; i++) publisher.publishDue(50);
        var observed = messages.status(activity.getId());
        assertTrue(((Number) observed.get("completedInWindow")).longValue() > 0);
        assertEquals(0L, observed.get("backlog")); assertEquals(false, observed.get("capacityVerified"));
        outbox.append("dead-"+activity.getId(), "campaign.conversion", activity.getId().toString(), "{}");
        publisher.markDead("dead-"+activity.getId(), "测试失败");
        assertEquals(1L, messages.status(activity.getId()).get("dead"));
        assertEquals("FAILED", messages.status(activity.getId()).get("status"));
        var otherActivity = campaigns.create("无关活动", now, now.plusDays(1), now.plusDays(2));
        assertEquals(0L, messages.status(otherActivity.getId()).get("dead"));
        owner(); assertThrows(BizException.class, () -> messages.status(activity.getId()));
    }

    @Test void publicEntryUsesOnlyOptionalAuthenticatedIdentityAndStillAllowsAnonymousVisitors() throws Exception {
        var register = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/identity/register")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "username", "arrival_"+UUID.randomUUID().toString().substring(0,8), "password", "password123"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
        var account = json.readTree(register.getResponse().getContentAsByteArray()).path("data");
        long uid = account.path("userId").asLong(), merchantId = account.path("merchantId").asLong();
        UserContext.set(CurrentUser.builder().userId(uid).merchantId(merchantId).username("arrival").roles(Set.of(UserRole.OWNER,UserRole.USER)).build());
        LocalDateTime now = LocalDateTime.now();
        var template = templates.create(null, "预约券", IssuerScope.MERCHANT, ApplyScope.STOREWIDE, null, BenefitKind.AMOUNT_OFF, 10, now.minusDays(1), now.plusDays(2),
                "{\"maxClaimsPerUser\":1}", "{\"capAmount\":\"1.00\",\"validityPeriodHours\":24}");
        var a = campaigns.create("身份记录", now.minusHours(1), now.plusHours(1), now.plusDays(1));
        flow.saveOffer(a.getId(), template.getId(), 5, 1, "offer", new BigDecimal("5"), null); flow.openMain(a.getId()); campaigns.publish(a.getId());
        UserContext.clear();
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/public/activities/"+a.getId()+"/visit")
                .header("Authorization", "Bearer "+account.path("token").asText())
                .header("User-Agent", "Mozilla/5.0").contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
        var body = json.readTree(response.getResponse().getContentAsByteArray()).path("data");
        String credential = body.path("credential").asText(); assertFalse(credential.isBlank());
        var recorded = visits.selectOne(Wrappers.<CampaignVisit>lambdaQuery().eq(CampaignVisit::getVisitTokenHash,
                com.sayohno.marketing.common.hash.RequestHashes.sha256(credential)));
        assertEquals(uid, recorded.getUserId()); assertNull(UserContext.get());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/public/activities/"+a.getId()+"/visit")
                .header("Authorization", "Bearer expired").header("User-Agent", "Mozilla/5.0")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertEquals(1, visits.selectCount(Wrappers.<CampaignVisit>lambdaQuery().eq(CampaignVisit::getActivityId,a.getId()).isNull(CampaignVisit::getUserId)));
    }

    private void owner() {
        var merchant = new IamMerchant(); merchant.setName("功能验收"+UUID.randomUUID()); merchant.setStatus(1); merchants.insert(merchant);
        UserContext.set(CurrentUser.builder().userId(merchant.getId()).username("qa").merchantId(merchant.getId()).roles(Set.of(UserRole.OWNER,UserRole.USER)).build());
    }
    private CampaignActivity historical(LocalDateTime start, String type) {
        var activity = campaigns.create("历史样本", start.minusDays(1), start, start.plusHours(1), type, null);
        activity.setStatus(1); activities.updateById(activity); return activity;
    }
    private CampaignChannel channel(long activityId, String name) {
        var channel = new CampaignChannel(); channel.setActivityId(activityId); channel.setMerchantId(UserContext.require().getMerchantId());
        channel.setName(name); channel.setChannelCode(UUID.randomUUID().toString().substring(0,8)); channel.setIdempotencyKey(UUID.randomUUID().toString()); channel.setStatus(1);
        channels.insert(channel); return channel;
    }
    private void reserve(long activityId, Long channelId, long uid, LocalDateTime when, boolean cancelled) {
        var row = new CampaignReservation(); row.setActivityId(activityId); row.setChannelId(channelId); row.setUserId(uid);
        row.setStatus(cancelled ? 2 : 1); row.setReservedAt(when); if (cancelled) row.setCancelledAt(when.plusMinutes(1)); reservations.insert(row);
    }
    private void visit(long activityId, Long uid, String visitor, LocalDateTime when, boolean valid) {
        var row = new CampaignVisit(); row.setActivityId(activityId); row.setUserId(uid); row.setVisitorId(visitor); row.setVisitTokenHash(UUID.randomUUID().toString());
        row.setVisitedAt(when); row.setExpireAt(when.plusMinutes(30)); row.setValidFlag(valid ? 1 : 0); visits.insert(row);
    }
}
