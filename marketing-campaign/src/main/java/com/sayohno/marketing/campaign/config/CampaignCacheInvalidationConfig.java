package com.sayohno.marketing.campaign.config;

import com.sayohno.marketing.campaign.service.CampaignRateLimitService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;

/**
 * 活动限流策略跨实例失效广播订阅配置：仅在 Redis 启用且存在连接工厂时生效。
 */
@Configuration
@ConditionalOnProperty(prefix = "marketing.redis", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(RedisConnectionFactory.class)
public class CampaignCacheInvalidationConfig {

    @Bean(name = "campaignRateLimitListenerContainer")
    public RedisMessageListenerContainer campaignRateLimitListenerContainer(RedisConnectionFactory connectionFactory,
                                                                              CampaignRateLimitService rateLimitService) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                (message, pattern) -> rateLimitService.handleInvalidation(new String(message.getBody(), StandardCharsets.UTF_8)),
                new ChannelTopic(CampaignRateLimitService.INVALIDATION_CHANNEL));
        return container;
    }
}
