package com.sayohno.marketing.flow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sayohno.marketing.campaign.dao.entity.CampaignActivity;
import com.sayohno.marketing.campaign.dao.mapper.CampaignActivityMapper;
import com.sayohno.marketing.campaign.service.CampaignFlowService;
import com.sayohno.marketing.campaign.service.CampaignLinkListService;
import com.sayohno.marketing.campaign.service.CampaignService;
import com.sayohno.marketing.campaign.web.dto.LinkListEntry;
import com.sayohno.marketing.common.context.CurrentUser;
import com.sayohno.marketing.common.context.UserContext;
import com.sayohno.marketing.common.context.UserRole;
import com.sayohno.marketing.shortlink.dao.entity.LinkAccessLog;
import com.sayohno.marketing.shortlink.dao.mapper.LinkAccessLogMapper;
import com.sayohno.marketing.shortlink.dao.mapper.LinkMapper;
import com.sayohno.marketing.shortlink.service.LinkDirectoryService;
import com.sayohno.marketing.support.MemoryStockConfiguration;
import com.sayohno.marketing.support.TestIdentityConfiguration;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestIdentityConfiguration.class, MemoryStockConfiguration.class, LinkDirectoryFlowTest.CountConfig.class})
class LinkDirectoryFlowTest {
    @Autowired CampaignLinkListService lists;
    @Autowired CampaignService campaigns;
    @Autowired CampaignFlowService flow;
    @Autowired CampaignActivityMapper activities;
    @Autowired LinkDirectoryService links;
    @Autowired LinkMapper linkRows;
    @Autowired LinkAccessLogMapper accessLogs;
    @Autowired QueryCounter queries;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @AfterEach void clear() { UserContext.clear(); }

    @Test void pagedEntriesKeepScopeCountsAndAttributionWithBoundedQueries() throws Exception {
        var owner = register("MERCHANT");
        as(owner);
        LocalDateTime now = LocalDateTime.now();
        var created = campaigns.create("列表主活动", now.minusHours(2), now.minusHours(1), now.plusDays(2));
        var main = flow.openMain(created.getId());
        var activity = activities.selectById(created.getId()); activity.setStatus(1); activities.updateById(activity);
        var channel = flow.addChannel(activity.getId(), "微信入口", "list-wx");
        var branch = flow.addChannel(activity.getId(), "短信入口", "list-sms");
        var plain = links.createLink(null, "https://example.com/offer", now.plusDays(1), "list-plain", null, null, "100%_礼遇", null, activity.getId());
        var expired = links.createLink(null, "https://example.com/expired", now.plusDays(1), "list-expired", null, null, "过期外链", null, null);
        expired.setValidDate(now.minusMinutes(1)); linkRows.updateById(expired);
        var disabled = links.createLink(null, "https://example.com/off", null, "list-off", null, null, "停用外链", null, null);
        links.updateLink(disabled.getId(), null, null, 1);
        var recycled = links.createLink(null, "https://example.com/recycled", null, "list-recycled", null, null, "回收外链", null, null);
        links.recycle(recycled.getId());
        flow.entry(main.get("shortUri").toString(), null, "landing-a");
        flow.entry(main.get("shortUri").toString(), null, "landing-a");
        flow.entry(main.get("shortUri").toString(), channel.getChannelCode(), "landing-a");
        flow.entry(main.get("shortUri").toString(), branch.getChannelCode(), "landing-b");
        log(((Number) main.get("id")).longValue(), "same-visitor"); log(((Number) main.get("id")).longValue(), "same-visitor");
        log(plain.getId(), "same-visitor"); log(plain.getId(), "anonymous"); log(recycled.getId(), "ignored");
        queries.count.set(0);
        var initial = lists.page(1, 100, null, null, false, "ALL");
        assertEquals(6L, initial.get("total"));
        assertEquals(6, queries.count.get());
        var summary = (Map<?, ?>) initial.get("summary");
        assertEquals(4L, summary.get("enabled"));
        assertEquals(2L, summary.get("visits")); // 同一访客跨两条短链分别计数，渠道不重复加入主链汇总。
        var rows = records(initial);
        var mainRow = rows.stream().filter(r -> r.getEntryKey().equals("m-" + activity.getId())).findFirst().orElseThrow();
        var channelRow = rows.stream().filter(r -> channel.getId().equals(r.getChannelId())).findFirst().orElseThrow();
        var plainRow = rows.stream().filter(r -> plain.getId().equals(r.getLinkId())).findFirst().orElseThrow();
        assertEquals(1L, mainRow.getClicks());
        assertEquals(1L, mainRow.getVisits());
        assertEquals(0L, channelRow.getClicks());
        assertEquals(1L, channelRow.getVisits());
        assertEquals(1L, plainRow.getClicks());
        assertEquals(1L, plainRow.getVisits());
        assertEquals("已过期", rows.stream().filter(r -> expired.getId().equals(r.getLinkId())).findFirst().orElseThrow().getPage());
        assertEquals(1L, lists.page(1, 20, "100%_", null, false, "ALL").get("total"));
        assertEquals(2L, lists.page(1, 20, null, null, true, "ALL").get("total"));
        assertEquals(4L, lists.page(1, 20, null, activity.getId(), false, "ACTIVE").get("total"));
        assertEquals(2L, lists.page(1, 20, null, null, false, "INACTIVE").get("total"));
        var page1 = lists.page(1, 2, null, null, false, "ALL");
        var page2 = lists.page(2, 2, null, null, false, "ALL");
        assertTrue(records(page1).stream().noneMatch(a -> records(page2).stream().anyMatch(b -> a.getEntryKey().equals(b.getEntryKey()))));
        assertEquals(summary, page2.get("summary"));
        assertEquals(3L, lists.page(Long.MAX_VALUE, 2, null, null, false, "ALL").get("page"));
        campaigns.setMainEnabled(activity.getId(), false);
        assertEquals(1L, ((Map<?, ?>) lists.page(1, 20, null, null, false, "ALL").get("summary")).get("enabled"));
        campaigns.setMainEnabled(activity.getId(), true);
        for (int i = 0; i < 30; i++) campaigns.create("批量草稿" + i, now, now.plusHours(1), now.plusDays(1));
        queries.count.set(0);
        var many = lists.page(1, 100, null, null, false, "ALL");
        assertEquals(36L, many.get("total")); assertEquals(6, queries.count.get());
        var options = lists.options(null, 1);
        assertEquals(20, ((List<?>) options.get("records")).size()); assertEquals(true, options.get("hasMore"));
        assertEquals(11, ((List<?>) lists.options(null, 2).get("records")).size());
        queries.count.set(0); assertEquals(activity.getId(), lists.spotlight().getId()); assertEquals(1, queries.count.get());

        var other = register("MERCHANT"); as(other);
        var foreign = campaigns.create("外部工作空间", now, now.plusHours(1), now.plusDays(1)); flow.openMain(foreign.getId());
        as(owner);
        assertEquals(0L, lists.page(1, 20, null, foreign.getId(), false, "ALL").get("total"));
        UserContext.clear();
        mvc.perform(get("/api/merchant/link-directory").header("Authorization", "Bearer " + owner.path("token").asText()).param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value("36"))
                .andExpect(jsonPath("$.data.records.length()").value(2));
        var user = register("USER");
        mvc.perform(get("/api/merchant/link-directory").header("Authorization", "Bearer " + user.path("token").asText()))
                .andExpect(status().isForbidden());
    }

    private void log(long link, String visitor) {
        var row = new LinkAccessLog(); row.setLinkId(link); row.setVisitorId(visitor); row.setEventId(UUID.randomUUID().toString());
        row.setVisitTime(LocalDateTime.now()); accessLogs.insert(row);
    }
    @SuppressWarnings("unchecked") private List<LinkListEntry> records(Map<String, Object> data) { return (List<LinkListEntry>) data.get("records"); }
    private JsonNode register(String type) throws Exception {
        var result = mvc.perform(post("/api/identity/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", "l_" + UUID.randomUUID().toString().replace("-", "").substring(0,10), "password", "testPassword123", "registerType", type, "merchantName", "列表测试"))))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("data");
    }
    private void as(JsonNode user) {
        UserContext.set(CurrentUser.builder().userId(user.path("userId").asLong()).merchantId(user.path("merchantId").asLong())
                .roles(Set.of(UserRole.OWNER, UserRole.USER)).build());
    }
    @TestConfiguration static class CountConfig { @Bean QueryCounter queryCounter() { return new QueryCounter(); } }
    @Intercepts(@Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    static class QueryCounter implements Interceptor {
        final AtomicInteger count = new AtomicInteger();
        @Override public Object intercept(Invocation invocation) throws Throwable {
            if (((MappedStatement) invocation.getArgs()[0]).getId().contains("CampaignLinkListMapper")) count.incrementAndGet();
            return invocation.proceed();
        }
    }
}
