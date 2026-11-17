package com.sayohno.marketing.identity.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@ConditionalOnProperty(prefix = "marketing.redis", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RedisSessionConfig {

    @Bean
    public SessionStore sessionStore(StringRedisTemplate redis, ObjectMapper objectMapper) {
        return new RedisSessionStore(redis, objectMapper);
    }
}
