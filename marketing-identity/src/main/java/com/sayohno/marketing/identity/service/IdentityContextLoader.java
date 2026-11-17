package com.sayohno.marketing.identity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sayohno.marketing.common.config.MarketingProperties;
import com.sayohno.marketing.common.context.CurrentUser;
import com.sayohno.marketing.common.context.UserRole;
import com.sayohno.marketing.identity.IamStatus;
import com.sayohno.marketing.identity.dao.entity.IamMerchantMember;
import com.sayohno.marketing.identity.dao.entity.IamShop;
import com.sayohno.marketing.identity.dao.entity.IamUser;
import com.sayohno.marketing.identity.dao.mapper.IamMerchantMemberMapper;
import com.sayohno.marketing.identity.dao.mapper.IamShopMapper;
import com.sayohno.marketing.identity.dao.mapper.IamUserMapper;
import com.sayohno.marketing.identity.session.UserSession;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class IdentityContextLoader {
    private final IamUserMapper userMapper;
    private final IamMerchantMemberMapper memberMapper;
    private final IamShopMapper shopMapper;
    private final com.sayohno.marketing.identity.dao.mapper.IamMerchantMapper merchantMapper;
    private final MarketingProperties properties;
    private final Map<String, CachedContext> cache = new ConcurrentHashMap<>();

    public IdentityContextLoader(IamUserMapper userMapper,
                                 IamMerchantMemberMapper memberMapper,
                                 IamShopMapper shopMapper,
                                 com.sayohno.marketing.identity.dao.mapper.IamMerchantMapper merchantMapper,
                                 MarketingProperties properties) {
        this.userMapper = userMapper;
        this.memberMapper = memberMapper;
        this.shopMapper = shopMapper;
        this.merchantMapper = merchantMapper;
        this.properties = properties;
    }

    public CurrentUser load(UserSession session) {
        if (session == null || session.getUserId() == null) {
            return null;
        }
        long ttlMs = properties.getSession().getContextCacheTtl().toMillis();
        String cacheKey = session.getUserId() + ":" + session.getMerchantId() + ":" + session.getStoreCode();
        if (ttlMs > 0) {
            CachedContext cached = cache.get(cacheKey);
            if (cached != null && cached.loadedAt() + ttlMs > System.currentTimeMillis()) {
                return cached.user();
            }
        }
        CurrentUser loaded = loadFromDatabase(session);
        if (ttlMs > 0 && loaded != null) {
            if (cache.size() > 50_000) {
                cache.clear();
            }
            cache.put(cacheKey, new CachedContext(System.currentTimeMillis(), loaded));
        }
        return loaded;
    }

    private CurrentUser loadFromDatabase(UserSession session) {
        IamUser user = userMapper.selectById(session.getUserId());
        if (user == null || user.getStatus() == null || user.getStatus() != IamStatus.ENABLED) {
            return null;
        }
        Set<String> roles = new LinkedHashSet<>();
        roles.add(UserRole.USER);
        Long merchantId = null;
        if (session.getMerchantId() != null) {
            var workspace = merchantMapper.selectById(session.getMerchantId());
            if (workspace != null && Integer.valueOf(IamStatus.ENABLED).equals(workspace.getStatus())
                    && user.getId().equals(workspace.getOwnerUserId())) {
                merchantId = workspace.getId();
                roles.add(UserRole.OWNER);
            }
            IamMerchantMember member = memberMapper.selectOne(Wrappers.<IamMerchantMember>lambdaQuery()
                    .eq(IamMerchantMember::getMerchantId, session.getMerchantId())
                    .eq(IamMerchantMember::getUserId, user.getId())
                    .eq(IamMerchantMember::getStatus, IamStatus.ENABLED));
            if (member != null && UserRole.merchantRole(member.getRole())) {
                merchantId = member.getMerchantId();
                roles.add(member.getRole());
            }
        }
        String storeCode = null;
        if (merchantId != null && session.getStoreCode() != null) {
            IamShop shop = shopMapper.selectOne(Wrappers.<IamShop>lambdaQuery()
                    .eq(IamShop::getMerchantId, merchantId)
                    .eq(IamShop::getStoreCode, session.getStoreCode())
                    .eq(IamShop::getStatus, IamStatus.ENABLED));
            if (shop != null) {
                storeCode = shop.getStoreCode();
            }
        }
        return CurrentUser.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .merchantId(merchantId)
                .storeCode(storeCode)
                .roles(roles)
                .build();
    }

    private record CachedContext(long loadedAt, CurrentUser user) {
    }
}
