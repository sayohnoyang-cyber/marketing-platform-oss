package com.sayohno.marketing.flow;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sayohno.marketing.campaign.dao.entity.CampaignActivity;
import com.sayohno.marketing.campaign.dao.mapper.CampaignActivityMapper;
import com.sayohno.marketing.campaign.service.CampaignCreateService;
import com.sayohno.marketing.campaign.web.dto.ActivityRequest;
import com.sayohno.marketing.common.context.CurrentUser;
import com.sayohno.marketing.common.context.UserContext;
import com.sayohno.marketing.common.context.UserRole;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.coupon.enums.*;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import({TestIdentityConfiguration.class, MemoryStockConfiguration.class})
class ActivityCreationFlowTest {
    @Autowired CampaignCreateService create;
    @Autowired CampaignActivityMapper activities;
    @Autowired com.sayohno.marketing.campaign.service.CampaignFlowService flow;
    @Autowired com.sayohno.marketing.campaign.service.CampaignService campaign;
    @Autowired com.sayohno.marketing.coupon.service.OfferPlanService templates;
    @AfterEach void clear() { UserContext.clear(); }

    @Test void concurrentCreationAndLostResponsesReuseOneActivityPerWorkspace() throws Exception {
        long merchant = Math.abs(UUID.randomUUID().getMostSignificantBits() >>> 2);
        var request = request();
        var executor = Executors.newFixedThreadPool(4);
        var start = new CountDownLatch(1);
        List<CompletableFuture<Long>> pending;
        try {
            pending = java.util.stream.IntStream.range(0, 4).mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                asMerchant(merchant);
                try { start.await(); return create.create(request).getId(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                finally { UserContext.clear(); }
            }, executor)).toList();
            start.countDown();
            long id = pending.get(0).get(15, TimeUnit.SECONDS);
            for (var result : pending) assertEquals(id, result.get(15, TimeUnit.SECONDS));
            asMerchant(merchant);
            assertEquals(id, create.create(request).getId());
            assertEquals(1L, activities.selectCount(Wrappers.<CampaignActivity>lambdaQuery().eq(CampaignActivity::getMerchantId, merchant)));
            request.setName("更改了参数");
            assertThrows(BizException.class, () -> create.create(request));
            request.setName("精确开场活动");
            asMerchant(merchant + 1);
            assertNotEquals(id, create.create(request).getId());
        } finally { executor.shutdownNow(); }
    }

    @Test void failedCreationRollsBackRequestAndLegacyCallsRemainAvailable() {
        asMerchant(Math.abs(UUID.randomUUID().getMostSignificantBits() >>> 2));
        var request = request();
        var end = request.getEndTime();
        request.setEndTime(request.getStartTime().minusHours(1));
        assertThrows(BizException.class, () -> create.create(request));
        request.setEndTime(end);
        var saved = create.create(request);
        assertEquals(request.getStartTime(), saved.getStartTime());
        assertEquals(saved.getId(), create.create(request).getId());
        request.setIdempotencyKey(null);
        assertNotEquals(create.create(request).getId(), create.create(request).getId());
    }

    @Test void repeatedPartialWritesKeepOneMainLinkAllocationAndChannel() {
        asMerchant(Math.abs(UUID.randomUUID().getMostSignificantBits() >>> 2));
        var request = request();
        var activity = create.create(request);
        var now = LocalDateTime.now();
        var template = templates.create(null, "续存测试券", IssuerScope.MERCHANT, ApplyScope.STOREWIDE, null, BenefitKind.AMOUNT_OFF, 10, now.minusDays(1), now.plusDays(3),
                "{\"maxClaimsPerUser\":1}", "{\"capAmount\":\"1.00\"}");
        assertEquals(flow.openMain(activity.getId()).get("id"), flow.openMain(activity.getId()).get("id"));
        String offer = UUID.randomUUID().toString();
        var first = flow.saveOffer(activity.getId(), template.getId(), 3, 1, offer, request.getBudgetAmount(), campaign.requireOwned(activity.getId()).getVersion());
        var retry = flow.saveOffer(activity.getId(), template.getId(), 3, 1, offer, request.getBudgetAmount(), campaign.requireOwned(activity.getId()).getVersion());
        assertEquals(first.getInventoryId(), retry.getInventoryId());
        assertEquals(7, templates.requireOwned(template.getId()).getInventoryQty());
        String channel = UUID.randomUUID().toString();
        assertEquals(flow.addChannel(activity.getId(), "微信", channel).getId(), flow.addChannel(activity.getId(), "微信", channel).getId());
        assertEquals(1, flow.channels(activity.getId()).size());
    }

    private ActivityRequest request() {
        var request = new ActivityRequest();
        request.setName("精确开场活动"); request.setActivityType("优惠券推广活动"); request.setBudgetAmount(new BigDecimal("100.00"));
        request.setStartTime(LocalDateTime.now().plusDays(1).withSecond(0).withNano(0)); request.setEndTime(request.getStartTime().plusHours(2));
        request.setIdempotencyKey(UUID.randomUUID().toString());
        return request;
    }

    private void asMerchant(long merchant) {
        UserContext.set(CurrentUser.builder().userId(merchant).merchantId(merchant).roles(Set.of(UserRole.OWNER, UserRole.USER)).build());
    }
}
