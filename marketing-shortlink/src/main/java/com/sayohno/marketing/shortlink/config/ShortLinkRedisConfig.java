package com.sayohno.marketing.shortlink.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.BaseMasterSlaveServersConfig;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Redisson 客户端配置：依据 Spring Boot RedisProperties 自动适配 Cluster、Sentinel 与单节点拓扑，
 * 并透传 SSL、数据库索引、认证凭据及超时参数，避免写死单机连接限制水平扩展。
 */
@Configuration
@ConditionalOnProperty(prefix = "marketing.redis", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ShortLinkRedisConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(RedisProperties redisProperties) {
        Config config = new Config();
        boolean ssl = redisProperties.getSsl() != null && redisProperties.getSsl().isEnabled();
        String scheme = ssl ? "rediss://" : "redis://";

        if (redisProperties.getCluster() != null && !CollectionUtils.isEmpty(redisProperties.getCluster().getNodes())) {
            var cluster = config.useClusterServers();
            cluster.addNodeAddress(formatNodes(scheme, redisProperties.getCluster().getNodes()));
            applyCommon(cluster, redisProperties);
            return Redisson.create(config);
        }

        if (redisProperties.getSentinel() != null
                && StringUtils.hasText(redisProperties.getSentinel().getMaster())
                && !CollectionUtils.isEmpty(redisProperties.getSentinel().getNodes())) {
            var sentinel = config.useSentinelServers()
                    .setMasterName(redisProperties.getSentinel().getMaster())
                    .setDatabase(redisProperties.getDatabase());
            sentinel.addSentinelAddress(formatNodes(scheme, redisProperties.getSentinel().getNodes()));
            applyCommon(sentinel, redisProperties);
            if (StringUtils.hasText(redisProperties.getSentinel().getPassword())) {
                sentinel.setSentinelPassword(redisProperties.getSentinel().getPassword());
            }
            return Redisson.create(config);
        }

        var single = config.useSingleServer()
                .setAddress(scheme + redisProperties.getHost() + ":" + redisProperties.getPort())
                .setDatabase(redisProperties.getDatabase());
        if (redisProperties.getTimeout() != null) {
            single.setTimeout((int) redisProperties.getTimeout().toMillis());
        }
        if (redisProperties.getConnectTimeout() != null) {
            single.setConnectTimeout((int) redisProperties.getConnectTimeout().toMillis());
        }
        if (StringUtils.hasText(redisProperties.getUsername())) {
            single.setUsername(redisProperties.getUsername());
        }
        if (StringUtils.hasText(redisProperties.getPassword())) {
            single.setPassword(redisProperties.getPassword());
        }
        return Redisson.create(config);
    }

    private static String[] formatNodes(String scheme, List<String> nodes) {
        return nodes.stream()
                .map(node -> (node.startsWith("redis://") || node.startsWith("rediss://")) ? node : scheme + node)
                .toArray(String[]::new);
    }

    private static void applyCommon(BaseMasterSlaveServersConfig<?> target, RedisProperties redisProperties) {
        if (redisProperties.getTimeout() != null) {
            target.setTimeout((int) redisProperties.getTimeout().toMillis());
        }
        if (redisProperties.getConnectTimeout() != null) {
            target.setConnectTimeout((int) redisProperties.getConnectTimeout().toMillis());
        }
        if (StringUtils.hasText(redisProperties.getUsername())) {
            target.setUsername(redisProperties.getUsername());
        }
        if (StringUtils.hasText(redisProperties.getPassword())) {
            target.setPassword(redisProperties.getPassword());
        }
    }
}
