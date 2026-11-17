package com.sayohno.marketing.shortlink.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sayohno.marketing.common.config.MarketingProperties;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.common.outbox.OutboxStore;
import com.sayohno.marketing.common.result.ErrorCode;
import com.sayohno.marketing.shortlink.bloom.BloomReadPolicy;
import com.sayohno.marketing.shortlink.dao.entity.Link;
import com.sayohno.marketing.shortlink.dao.mapper.LinkMapper;
import com.sayohno.marketing.shortlink.redis.ShortLinkRedisKeys;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class RedirectService {
    public static final String VISIT_EVENT = "shortlink.visit";
    private static final Logger log = LoggerFactory.getLogger(RedirectService.class);
    private static final String MISS = "__miss__";
    private final LinkMapper linkMapper;
    private final MarketingProperties properties;
    private final BloomReadPolicy bloomReadPolicy;
    private final ObjectProvider<RedissonClient> redisson;
    private final ObjectProvider<StringRedisTemplate> redis;
    private final OutboxStore outboxStore;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public RedirectService(LinkMapper linkMapper,
                           MarketingProperties properties,
                           BloomReadPolicy bloomReadPolicy,
                           ObjectProvider<RedissonClient> redisson,
                           ObjectProvider<StringRedisTemplate> redis,
                           OutboxStore outboxStore,
                           ObjectMapper objectMapper,
                           PlatformTransactionManager transactionManager) {
        this.linkMapper = linkMapper;
        this.properties = properties;
        this.bloomReadPolicy = bloomReadPolicy;
        this.redisson = redisson;
        this.redis = redis;
        this.outboxStore = outboxStore;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public Link resolve(String shortUri, String visitorId) {
        return resolve(shortUri, visitorId, true);
    }

    public Link resolve(String shortUri, String visitorId, boolean record) {
        if (shortUri == null || !shortUri.matches("[0-9A-Za-z]{2,12}")) {
            throw new BizException(ErrorCode.NOT_FOUND, "短链不存在");
        }
        String domain = PublicLinkAddress.host(properties.getShortlink().getPublicBaseUrl());
        String full = domain + "/s/" + shortUri;
        Link cached = readCache(full);
        if (cached != null) {
            ensureUsable(full, cached, true);
            if (record) recordVisit(cached, visitorId);
            return cached;
        }
        Link link = load(full, domain, shortUri);
        ensureUsable(full, link, false);
        rememberHit(full, link);
        if (record) recordVisit(link, visitorId);
        return link;
    }

    /** Uses the same redirect cache as public reads, but emits no visit event. */
    public java.util.Map<String, Object> warmMapping(Link link) {
        if (redis.getIfAvailable() == null || redisson.getIfAvailable() == null) return java.util.Map.of("status", "UNVERIFIED", "detail", "Redis或布隆服务未启用");
        ensureUsable(link.getFullShortUrl(), link, false);
        var bloom = redisson.getObject().<String>getBloomFilter(ShortLinkRedisKeys.BLOOM_URI);
        if (!bloom.isExists()) return java.util.Map.of("status", "UNVERIFIED", "detail", "布隆过滤器尚未恢复，未强行初始化");
        bloom.add(link.getFullShortUrl());
        rememberHit(link.getFullShortUrl(), link);
        Link readBack = readCache(link.getFullShortUrl());
        boolean matched = readBack != null && link.getId().equals(readBack.getId()) && link.getOriginUrl().equals(readBack.getOriginUrl()) && bloom.contains(link.getFullShortUrl());
        return java.util.Map.of("status", matched ? "SUCCEEDED" : "FAILED", "detail", matched ? "主链映射与布隆已读回验证，未生成访问统计" : "主链缓存读回不一致");
    }

    private void ensureUsable(String full, Link link, boolean fromCache) {
        if (link == null || link.getDelTime() == null || link.getDelTime() != 0 || link.getId() == null) {
            if (fromCache) {
                deleteCache(full);
            } else {
                rememberMiss(full);
            }
            throw new BizException(ErrorCode.NOT_FOUND, "短链不存在");
        }
        if (link.getEnableStatus() != null && link.getEnableStatus() != 0) {
            deleteCache(full);
            throw new BizException(ErrorCode.NOT_FOUND, "短链已停用");
        }
        if (link.getValidDate() != null && !LocalDateTime.now().isBefore(link.getValidDate())) {
            deleteCache(full);
            throw new BizException(ErrorCode.NOT_FOUND, "短链已过期");
        }
    }

    private Link load(String full, String domain, String shortUri) {
        if (!allowLookup(full)) {
            return null;
        }
        return linkMapper.selectOne(Wrappers.<Link>lambdaQuery()
                .eq(Link::getDomain, domain)
                .eq(Link::getShortUri, shortUri)
                .eq(Link::getDelTime, 0L));
    }

    private boolean allowLookup(String full) {
        RedissonClient client = redisson.getIfAvailable();
        StringRedisTemplate template = redis.getIfAvailable();
        if (client == null || template == null) {
            return true;
        }
        boolean ready = "ready".equals(template.opsForValue().get(ShortLinkRedisKeys.BLOOM_READY));
        RBloomFilter<String> bloom = client.getBloomFilter(ShortLinkRedisKeys.BLOOM_URI);
        boolean contains = bloom.isExists() && bloom.contains(full);
        return bloomReadPolicy.continueLookup(ready, contains);
    }

    private Link readCache(String full) {
        StringRedisTemplate template = redis.getIfAvailable();
        if (template == null) {
            return null;
        }
        String value = template.opsForValue().get(ShortLinkRedisKeys.gotoCache(full));
        if (value == null || value.isBlank()) {
            if (MISS.equals(template.opsForValue().get(ShortLinkRedisKeys.gotoMiss(full)))) {
                return new Link();
            }
            return null;
        }
        try {
            var node = objectMapper.readTree(value);
            Link link = new Link();
            link.setId(node.path("id").asLong());
            link.setOriginUrl(node.path("origin").asText());
            link.setEnableStatus(node.path("enable").asInt(0));
            link.setDelTime(node.path("delTime").asLong(0));
            if (node.hasNonNull("validDate")) {
                link.setValidDate(LocalDateTime.parse(node.get("validDate").asText()));
            }
            link.setFullShortUrl(full);
            if (node.hasNonNull("updateTime")) {
                link.setUpdateTime(LocalDateTime.parse(node.get("updateTime").asText()));
            }
            return link;
        } catch (Exception exception) {
            deleteCache(full);
            return null;
        }
    }

    private void rememberHit(String full, Link observed) {
        StringRedisTemplate template = redis.getIfAvailable();
        if (template == null || observed.getId() == null) {
            return;
        }
        Link current = linkMapper.selectById(observed.getId());
        if (current == null || current.getUpdateTime() == null || !current.getUpdateTime().equals(observed.getUpdateTime())
                || current.getEnableStatus() == null || current.getEnableStatus() != 0
                || current.getDelTime() == null || current.getDelTime() != 0) {
            return;
        }
        Duration ttl = Duration.ofHours(12);
        if (current.getValidDate() != null) {
            Duration remaining = Duration.between(LocalDateTime.now(), current.getValidDate());
            if (remaining.isNegative() || remaining.isZero()) {
                return;
            }
            if (remaining.compareTo(ttl) < 0) {
                ttl = remaining;
            }
        }
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", current.getId());
        node.put("origin", current.getOriginUrl());
        node.put("enable", current.getEnableStatus() == null ? 0 : current.getEnableStatus());
        node.put("delTime", current.getDelTime());
        if (current.getValidDate() != null) {
            node.put("validDate", current.getValidDate().toString());
        }
        node.put("updateTime", current.getUpdateTime().toString());
        template.opsForValue().set(ShortLinkRedisKeys.gotoCache(full), node.toString(), ttl);
        template.delete(ShortLinkRedisKeys.gotoMiss(full));
    }

    private void deleteCache(String full) {
        StringRedisTemplate template = redis.getIfAvailable();
        if (template == null) {
            return;
        }
        template.delete(ShortLinkRedisKeys.gotoCache(full));
    }

    private void rememberMiss(String full) {
        StringRedisTemplate template = redis.getIfAvailable();
        if (template == null) {
            return;
        }
        template.opsForValue().set(ShortLinkRedisKeys.gotoMiss(full), MISS, Duration.ofSeconds(60));
    }

    private void recordVisit(Link link, String visitorId) {
        if (link.getId() == null) {
            return;
        }
        String eventId = UUID.randomUUID().toString().replace("-", "");
        String cleaned = visitorId == null ? "" : visitorId.replaceAll("[^A-Za-z0-9_.:-]", "");
        if (cleaned.isBlank()) {
            cleaned = "anonymous";
        }
        String visitor = cleaned.substring(0, Math.min(64, cleaned.length()));
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("linkId", link.getId());
        payload.put("visitorId", visitor);
        payload.put("occurredAt", LocalDateTime.now().toString());
        try {
            transactionTemplate.executeWithoutResult(status -> outboxStore.append(eventId, VISIT_EVENT,
                    String.valueOf(link.getId()), payload.toString()));
        } catch (RuntimeException exception) {
            log.error("短链统计事件未写入，跳转继续。linkId={}", link.getId(), exception);
        }
    }
}
