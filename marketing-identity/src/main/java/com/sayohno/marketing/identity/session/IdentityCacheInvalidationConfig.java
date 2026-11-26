package com.sayohno.marketing.identity.session;

import com.sayohno.marketing.identity.service.IdentityContextLoader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;

/**
 * 身份上下文缓存跨实例失效广播订阅配置：仅在 Redis 启用且存在连接工厂时生效。
 */
@Configuration
@ConditionalOnProperty(prefix = "marketing.redis", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(RedisConnectionFactory.class)
public class IdentityCacheInvalidationConfig {

    @Bean(name = "identityCacheListenerContainer")
    public RedisMessageListenerContainer identityCacheListenerContainer(RedisConnectionFactory connectionFactory,
                                                                        IdentityContextLoader contextLoader) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                (message, pattern) -> contextLoader.handleInvalidation(new String(message.getBody(), StandardCharsets.UTF_8)),
                new ChannelTopic(IdentityContextLoader.INVALIDATION_CHANNEL));
        return container;
    }
}
