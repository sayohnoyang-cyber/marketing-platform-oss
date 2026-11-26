package com.sayohno.marketing.identity.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.common.result.ErrorCode;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;

public class RedisSessionStore implements SessionStore {
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisSessionStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(String token, UserSession session, Duration ttl) {
        try {
            redis.opsForValue().set(IdentityRedisKeys.session(token), objectMapper.writeValueAsString(session), ttl);
        } catch (JsonProcessingException exception) {
            throw new BizException(ErrorCode.SYSTEM, "会话无法保存");
        }
    }

    @Override
    public Optional<UserSession> find(String token) {
        String json = redis.opsForValue().get(IdentityRedisKeys.session(token));
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, UserSession.class));
        } catch (JsonProcessingException exception) {
            delete(token);
            return Optional.empty();
        }
    }

    @Override
    public void touch(String token, Duration ttl) {
        redis.expire(IdentityRedisKeys.session(token), ttl);
    }

    @Override
    public void delete(String token) {
        redis.delete(IdentityRedisKeys.session(token));
    }

    @Override
    public long currentVersion(long userId) {
        String raw = redis.opsForValue().get(IdentityRedisKeys.userSessionVersion(userId));
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    @Override
    public long bumpVersion(long userId) {
        Long version = redis.opsForValue().increment(IdentityRedisKeys.userSessionVersion(userId));
        return version == null ? 0L : version;
    }
}
