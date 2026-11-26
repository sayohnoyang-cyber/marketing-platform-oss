package com.sayohno.marketing.identity.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sayohno.marketing.common.config.MarketingProperties;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.common.result.ErrorCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 登录失败防爆破限流器：
 * 15 分钟时间窗口内同一账号连续认证失败达到 10 次后拒绝尝试（返回 429 RATE_LIMITED）；
 * 多实例部署且开启 Redis 时使用 Redis 原子计数跨节点共享，单机或禁用 Redis 时自动退化为 Caffeine 本地计数。
 */
@Component
public class LoginAttemptLimiter {
    static final int MAX_ATTEMPTS = 10;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final String KEY_PREFIX = "identity:login:fail:";

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final MarketingProperties properties;
    private final Cache<String, AtomicInteger> localFailures = Caffeine.newBuilder()
            .expireAfterWrite(WINDOW)
            .maximumSize(50_000)
            .build();

    public LoginAttemptLimiter(ObjectProvider<StringRedisTemplate> redisProvider, MarketingProperties properties) {
        this.redisProvider = redisProvider;
        this.properties = properties;
    }

    /**
     * 登录认证前校验是否处于失败锁定窗口，超限则直接阻断。
     */
    public void assertAllowed(String username) {
        String key = normalize(username);
        if (key == null) {
            return;
        }
        if (currentFailures(key) >= MAX_ATTEMPTS) {
            throw new BizException(ErrorCode.RATE_LIMITED, "登录失败次数过多，请 15 分钟后再试");
        }
    }

    /**
     * 密码校验失败后累加失败次数，并设置 15 分钟固定窗口过期时间。
     */
    public void recordFailure(String username) {
        String key = normalize(username);
        if (key == null) {
            return;
        }
        StringRedisTemplate redis = activeRedis();
        if (redis != null) {
            String redisKey = KEY_PREFIX + key;
            Long count = redis.opsForValue().increment(redisKey);
            if (count != null && count == 1L) {
                redis.expire(redisKey, WINDOW);
            }
            return;
        }
        AtomicInteger counter = localFailures.get(key, ignored -> new AtomicInteger());
        if (counter != null) {
            counter.incrementAndGet();
        }
    }

    /**
     * 登录成功后清除该账号的失败计数。
     */
    public void clear(String username) {
        String key = normalize(username);
        if (key == null) {
            return;
        }
        StringRedisTemplate redis = activeRedis();
        if (redis != null) {
            redis.delete(KEY_PREFIX + key);
            return;
        }
        localFailures.invalidate(key);
    }

    private int currentFailures(String key) {
        StringRedisTemplate redis = activeRedis();
        if (redis != null) {
            String raw = redis.opsForValue().get(KEY_PREFIX + key);
            if (raw == null || raw.isBlank()) {
                return 0;
            }
            try {
                return Integer.parseInt(raw);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        AtomicInteger counter = localFailures.getIfPresent(key);
        return counter == null ? 0 : counter.get();
    }

    private StringRedisTemplate activeRedis() {
        if (!properties.getRedis().isEnabled() || redisProvider == null) {
            return null;
        }
        return redisProvider.getIfAvailable();
    }

    private static String normalize(String username) {
        if (username == null) {
            return null;
        }
        String trimmed = username.trim().toLowerCase(Locale.ROOT);
        return trimmed.isEmpty() ? null : trimmed;
    }
}
