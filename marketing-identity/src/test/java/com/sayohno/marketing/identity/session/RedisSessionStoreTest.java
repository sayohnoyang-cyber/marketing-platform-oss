package com.sayohno.marketing.identity.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisSessionStoreTest {
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> values;

    @Test
    void storesSessionUnderSingleIdentityNamespace() {
        when(redis.opsForValue()).thenReturn(values);
        RedisSessionStore store = new RedisSessionStore(redis, new ObjectMapper());
        UserSession session = new UserSession();
        session.setUserId(9L);
        store.save("abc", session, Duration.ofMinutes(30));
        verify(values).set(eq("identity:session:abc"), anyString(), eq(Duration.ofMinutes(30)));
        store.touch("abc", Duration.ofMinutes(15));
        verify(redis).expire("identity:session:abc", Duration.ofMinutes(15));
    }
}
