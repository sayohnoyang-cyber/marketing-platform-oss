package com.sayohno.marketing.identity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.sayohno.marketing.common.config.MarketingProperties;
import com.sayohno.marketing.common.context.CurrentUser;
import com.sayohno.marketing.common.context.UserRole;
import com.sayohno.marketing.identity.IamStatus;
import com.sayohno.marketing.identity.dao.entity.IamMerchantMember;
import com.sayohno.marketing.identity.dao.entity.IamShop;
import com.sayohno.marketing.identity.dao.entity.IamUser;
import com.sayohno.marketing.identity.dao.mapper.IamMerchantMapper;
import com.sayohno.marketing.identity.dao.mapper.IamMerchantMemberMapper;
import com.sayohno.marketing.identity.dao.mapper.IamShopMapper;
import com.sayohno.marketing.identity.dao.mapper.IamUserMapper;
import com.sayohno.marketing.identity.session.UserSession;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 用户身份与工作空间权限上下文加载器：
 * 使用 Caffeine 有界本地缓存替换无界 Map，并结合 Redis Pub/Sub 广播在改密、成员/角色变更时即时失效各实例缓存。
 */
@Service
public class IdentityContextLoader {
    public static final String INVALIDATION_CHANNEL = "identity:cache:evict";

    private final IamUserMapper userMapper;
    private final IamMerchantMemberMapper memberMapper;
    private final IamShopMapper shopMapper;
    private final IamMerchantMapper merchantMapper;
    private final MarketingProperties properties;
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final Cache<String, CurrentUser> cache;

    public IdentityContextLoader(IamUserMapper userMapper,
                                 IamMerchantMemberMapper memberMapper,
                                 IamShopMapper shopMapper,
                                 IamMerchantMapper merchantMapper,
                                 MarketingProperties properties) {
        this(userMapper, memberMapper, shopMapper, merchantMapper, properties, null);
    }

    @Autowired
    public IdentityContextLoader(IamUserMapper userMapper,
                                 IamMerchantMemberMapper memberMapper,
                                 IamShopMapper shopMapper,
                                 IamMerchantMapper merchantMapper,
                                 MarketingProperties properties,
                                 ObjectProvider<StringRedisTemplate> redisProvider) {
        this.userMapper = userMapper;
        this.memberMapper = memberMapper;
        this.shopMapper = shopMapper;
        this.merchantMapper = merchantMapper;
        this.properties = properties;
        this.redisProvider = redisProvider;
        this.cache = Caffeine.newBuilder()
                .maximumSize(50_000)
                .expireAfter(new Expiry<String, CurrentUser>() {
                    @Override
                    public long expireAfterCreate(String key, CurrentUser value, long currentTime) {
                        return Math.max(0L, properties.getSession().getContextCacheTtl().toNanos());
                    }

                    @Override
                    public long expireAfterUpdate(String key, CurrentUser value, long currentTime, long currentDuration) {
                        return Math.max(0L, properties.getSession().getContextCacheTtl().toNanos());
                    }

                    @Override
                    public long expireAfterRead(String key, CurrentUser value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .build();
    }

    public CurrentUser load(UserSession session) {
        if (session == null || session.getUserId() == null) {
            return null;
        }
        long ttlNanos = properties.getSession().getContextCacheTtl().toNanos();
        String cacheKey = session.getUserId() + ":" + session.getMerchantId() + ":" + session.getStoreCode();
        if (ttlNanos > 0) {
            CurrentUser cached = cache.getIfPresent(cacheKey);
            if (cached != null) {
                return cached;
            }
        }
        CurrentUser loaded = loadFromDatabase(session);
        if (ttlNanos > 0 && loaded != null) {
            cache.put(cacheKey, loaded);
        }
        return loaded;
    }

    /**
     * 失效指定用户的本地缓存，并通过 Redis Pub/Sub 广播通知集群内其他节点同步清除。
     */
    public void evictUser(long userId) {
        evictUserLocally(userId);
        publishInvalidation("u:" + userId);
    }

    /**
     * 失效指定工作空间的本地缓存并广播。
     */
    public void evictMerchant(long merchantId) {
        evictMerchantLocally(merchantId);
        publishInvalidation("m:" + merchantId);
    }

    /**
     * 处理来自 Redis Pub/Sub 的集群缓存失效通知。
     */
    public void handleInvalidation(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        if (message.startsWith("u:")) {
            try {
                evictUserLocally(Long.parseLong(message.substring(2)));
            } catch (NumberFormatException ignored) {
            }
        } else if (message.startsWith("m:")) {
            try {
                evictMerchantLocally(Long.parseLong(message.substring(2)));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void evictUserLocally(long userId) {
        String prefix = userId + ":";
        cache.asMap().keySet().removeIf(key -> key.startsWith(prefix));
    }

    private void evictMerchantLocally(long merchantId) {
        cache.asMap().entrySet().removeIf(entry -> Long.valueOf(merchantId).equals(entry.getValue().getMerchantId()));
    }

    private void publishInvalidation(String payload) {
        StringRedisTemplate redis = redisProvider == null ? null : redisProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        try {
            redis.convertAndSend(INVALIDATION_CHANNEL, payload);
        } catch (RuntimeException ignored) {
            // 广播失败时仍依赖短 TTL 兜底，不影响主业务流程。
        }
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
}
