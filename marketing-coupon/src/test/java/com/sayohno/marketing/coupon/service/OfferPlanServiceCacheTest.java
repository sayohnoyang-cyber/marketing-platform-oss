package com.sayohno.marketing.coupon.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sayohno.marketing.common.context.CurrentUser;
import com.sayohno.marketing.common.context.UserContext;
import com.sayohno.marketing.common.context.UserRole;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.coupon.dao.entity.OfferPlan;
import com.sayohno.marketing.coupon.dao.mapper.OfferPlanAuditMapper;
import com.sayohno.marketing.coupon.dao.mapper.OfferPlanMapper;
import com.sayohno.marketing.coupon.enums.OfferPlanStatus;
import com.sayohno.marketing.coupon.stock.OfferStockGateway;
import com.sayohno.marketing.identity.service.MerchantAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OfferPlanServiceCacheTest {

    @AfterEach
    void clear() {
        UserContext.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void publicOfferReadsCacheFirstAndReviseEvictsIt() {
        OfferPlanMapper templateMapper = mock(OfferPlanMapper.class);
        OfferPlanAuditMapper auditMapper = mock(OfferPlanAuditMapper.class);
        MerchantAccessService accessService = mock(MerchantAccessService.class);
        OfferStockGateway stockGateway = mock(OfferStockGateway.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(ops);

        Map<String, String> store = new HashMap<>();
        when(ops.get(anyString())).thenAnswer(inv -> store.get(inv.<String>getArgument(0)));
        doAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(ops).set(anyString(), anyString(), any(Duration.class));
        when(redisTemplate.delete(anyString())).thenAnswer(inv -> store.remove(inv.<String>getArgument(0)) != null);

        LocalDateTime until = LocalDateTime.of(2026, 12, 31, 23, 59);
        OfferPlan plan = new OfferPlan();
        plan.setId(101L);
        plan.setMerchantId(202L);
        plan.setTitle("满减券");
        plan.setBenefitPolicy("{\"minSpend\":\"100\",\"capAmount\":\"20\"}");
        plan.setClaimPolicy("{\"maxClaimsPerUser\":1}");
        plan.setUsableFrom(until.minusDays(10));
        plan.setUsableUntil(until);
        plan.setStatus(OfferPlanStatus.OPEN);
        when(templateMapper.selectById(101L)).thenReturn(plan);

        OfferPlanService service = new OfferPlanService(templateMapper, auditMapper, accessService, stockGateway, redisProvider, new ObjectMapper());

        // 首次读取回源数据库并写入 Redis 缓存
        Map<String, Object> first = service.publicOffer(101L, 202L);
        assertEquals("满减券", first.get("couponName"));
        assertEquals(until, first.get("couponValidEndTime"));
        assertEquals(OfferPlanStatus.OPEN, first.get("couponStatus"));
        assertFalse(first.containsKey("merchantId"));
        verify(templateMapper, times(1)).selectById(101L);

        // 第二次读取直接命中 Redis 缓存，不再穿透查询数据库
        Map<String, Object> second = service.publicOffer(101L, 202L);
        assertEquals(first, second);
        verify(templateMapper, times(1)).selectById(101L);

        // 命中缓存时依然校验工作空间归属，跨商家拒绝访问
        assertThrows(BizException.class, () -> service.publicOffer(101L, 999L));

        // 修改券方案后主动失效缓存
        CurrentUser owner = CurrentUser.builder().userId(202L).merchantId(202L).roles(Set.of(UserRole.OWNER)).build();
        when(accessService.requireMerchant()).thenReturn(owner);
        service.revise(101L, "新满减券", null, null, null, null);
        assertFalse(store.containsKey("coupon:offer:101"));
    }
}
