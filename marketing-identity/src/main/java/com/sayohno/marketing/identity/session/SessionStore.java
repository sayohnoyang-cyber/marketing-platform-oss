package com.sayohno.marketing.identity.session;

import java.time.Duration;
import java.util.Optional;

public interface SessionStore {
    void save(String token, UserSession session, Duration ttl);

    Optional<UserSession> find(String token);

    /**
     * 仅延长已有会话的过期时间，避免每次活跃请求重复序列化写入会话内容。
     */
    default void touch(String token, Duration ttl) {
        find(token).ifPresent(session -> save(token, session, ttl));
    }

    void delete(String token);

    /**
     * 读取用户当前有效会话版本号，默认为 0。
     */
    default long currentVersion(long userId) {
        return 0L;
    }

    /**
     * 递增用户会话版本号，使该用户此前签发的所有旧会话立即失效。
     */
    default long bumpVersion(long userId) {
        return 0L;
    }
}
