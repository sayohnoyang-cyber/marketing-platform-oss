package com.sayohno.marketing.shortlink.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sayohno.marketing.common.config.MarketingProperties;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.common.hash.RequestHashes;
import com.sayohno.marketing.common.result.ErrorCode;
import com.sayohno.marketing.identity.service.MerchantAccessService;
import com.sayohno.marketing.shortlink.dao.entity.Link;
import com.sayohno.marketing.shortlink.dao.entity.LinkCreateRequest;
import com.sayohno.marketing.shortlink.dao.entity.LinkGoto;
import com.sayohno.marketing.shortlink.dao.entity.LinkGroup;
import com.sayohno.marketing.shortlink.dao.mapper.LinkCreateRequestMapper;
import com.sayohno.marketing.shortlink.dao.mapper.LinkGotoMapper;
import com.sayohno.marketing.shortlink.dao.mapper.LinkGroupMapper;
import com.sayohno.marketing.shortlink.dao.mapper.LinkMapper;
import com.sayohno.marketing.shortlink.redis.ShortLinkRedisKeys;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class LinkDirectoryService {
    private static final char[] ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private final LinkGroupMapper groupMapper;
    private final LinkMapper linkMapper;
    private final LinkGotoMapper gotoMapper;
    private final LinkCreateRequestMapper createRequestMapper;
    private final MerchantAccessService merchantAccessService;
    private final MarketingProperties properties;
    private final ObjectProvider<StringRedisTemplate> redis;
    private final ObjectProvider<RedissonClient> redisson;
    private final ObjectProvider<com.sayohno.marketing.common.campaign.ActivityLinkGuard> activityGuards;

    public LinkDirectoryService(LinkGroupMapper groupMapper,
                                LinkMapper linkMapper,
                                LinkGotoMapper gotoMapper,
                                LinkCreateRequestMapper createRequestMapper,
                                MerchantAccessService merchantAccessService,
                                MarketingProperties properties,
                                ObjectProvider<StringRedisTemplate> redis,
                                ObjectProvider<RedissonClient> redisson,
                                ObjectProvider<com.sayohno.marketing.common.campaign.ActivityLinkGuard> activityGuards) {
        this.groupMapper = groupMapper;
        this.linkMapper = linkMapper;
        this.gotoMapper = gotoMapper;
        this.createRequestMapper = createRequestMapper;
        this.merchantAccessService = merchantAccessService;
        this.properties = properties;
        this.redis = redis;
        this.redisson = redisson;
        this.activityGuards = activityGuards;
    }

    @Transactional
    public LinkGroup createGroup(String name) {
        var merchant = merchantAccessService.requireMerchant();
        if (name == null || name.isBlank() || name.length() > 64) {
            throw new BizException(ErrorCode.VALIDATION, "分组名称不合法");
        }
        LinkGroup group = new LinkGroup();
        group.setGid(newCode("G", 8));
        group.setMerchantId(merchant.getMerchantId());
        group.setName(name.trim());
        group.setSortOrder(0);
        group.setStatus(1);
        groupMapper.insert(group);
        return group;
    }

    public List<LinkGroup> listGroups() {
        var merchant = merchantAccessService.requireMerchant();
        return groupMapper.selectList(Wrappers.<LinkGroup>lambdaQuery()
                .eq(LinkGroup::getMerchantId, merchant.getMerchantId())
                .orderByAsc(LinkGroup::getSortOrder)
                .orderByDesc(LinkGroup::getId));
    }

    @Transactional
    public Link createLink(String gid, String originUrl, LocalDateTime validDate, String idempotencyKey,
                           String forwardedHost, String forwardedProto) {
        return createLink(gid, originUrl, validDate, idempotencyKey, forwardedHost, forwardedProto, null, null);
    }

    @Transactional
    public Link createLink(String gid, String originUrl, LocalDateTime validDate, String idempotencyKey,
                           String forwardedHost, String forwardedProto, String name, String customCode) {
        return createLink(gid, originUrl, validDate, idempotencyKey, forwardedHost, forwardedProto, name, customCode, null);
    }

    @Transactional
    public Link createLink(String gid, String originUrl, LocalDateTime validDate, String idempotencyKey,
                           String forwardedHost, String forwardedProto, String name, String customCode, Long activityId) {
        merchantAccessService.requireMerchant();
        if (activityId != null) {
            var guard = activityGuards.getIfAvailable();
            if (guard == null) throw new BizException(ErrorCode.VALIDATION, "活动模块不可用");
            guard.assertLinkable(activityId);
        }
        LinkGroup group = gid == null || gid.isBlank() ? defaultGroup() : requireGroup(gid);
        if (name != null && name.length() > 128) throw new BizException(ErrorCode.VALIDATION, "短链名称不能超过128字");
        if (customCode != null && !customCode.matches("[A-Za-z0-9]{2,12}")) throw new BizException(ErrorCode.VALIDATION, "自定义后缀须为2到12位字母或数字");
        validateOrigin(originUrl);
        String base = PublicLinkAddress.base(properties.getShortlink().getPublicBaseUrl(),
                properties.getShortlink().isTrustForwarded(), forwardedHost, forwardedProto);
        String extra = name == null && customCode == null ? "" : "|name:" + (name == null ? 0 : name.trim().length()) + ":" + (name == null ? "" : name.trim()) + "|suffix:" + customCode;
        String hash = RequestHashes.sha256(group.getMerchantId() + "|" + group.getGid() + "|" + originUrl.trim() + "|" + validDate + extra + (activityId == null ? "" : "|activity:" + activityId));
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return insertLink(group, originUrl.trim(), validDate, base, name, customCode, activityId);
        }
        return createIdempotent(group, originUrl.trim(), validDate, base, idempotencyKey.trim(), hash, name, customCode, activityId);
    }

    private Link createIdempotent(LinkGroup group, String originUrl, LocalDateTime validDate, String base, String key, String hash, String name, String customCode, Long activityId) {
        LinkCreateRequest created = new LinkCreateRequest();
        created.setMerchantId(group.getMerchantId());
        created.setIdempotencyKey(key);
        created.setRequestHash(hash);
        try {
            createRequestMapper.insert(created);
        } catch (DataIntegrityViolationException ignored) {
            // 另一事务已占住同一幂等键，下面的行锁会等到它提交。
        }
        LinkCreateRequest locked = createRequestMapper.lockByKey(group.getMerchantId(), key);
        if (locked == null) {
            throw new BizException(ErrorCode.CONFLICT, "短链创建请求冲突");
        }
        if (!hash.equals(locked.getRequestHash())) {
            throw new BizException(ErrorCode.CONFLICT, "同一幂等键的短链参数不一致");
        }
        if (locked.getLinkId() != null) {
            Link existing = linkMapper.selectById(locked.getLinkId());
            if (existing != null) {
                return existing;
            }
        }
        Link link = insertLink(group, originUrl, validDate, base, name, customCode, activityId);
        locked.setLinkId(link.getId());
        createRequestMapper.updateById(locked);
        return link;
    }

    public List<Link> listLinks(String gid, boolean recycle) {
        var merchant = merchantAccessService.requireMerchant();
        var query = Wrappers.<Link>lambdaQuery().eq(Link::getMerchantId, merchant.getMerchantId());
        if (gid != null && !gid.isBlank()) query.eq(Link::getGid, requireGroup(gid).getGid());
        if (recycle) {
            query.gt(Link::getDelTime, 0L);
        } else {
            query.eq(Link::getDelTime, 0L);
        }
        return linkMapper.selectList(query.orderByDesc(Link::getId));
    }

    /** 对外普通短链修改入口：禁止越权修改活动主短链的地址、有效期或启停状态。 */
    @Transactional
    public Link updateLink(long linkId, String originUrl, LocalDateTime validDate, Integer enableStatus) {
        var guard = activityGuards.getIfAvailable();
        if (guard != null) {
            guard.assertNotActivityMainLink(linkId);
        }
        return updateManagedLink(linkId, originUrl, validDate, enableStatus);
    }

    /** 供活动模块内部同步改期与主链启停的受管更新方法，跳过普通短链防误改拦截。 */
    @Transactional
    public Link updateManagedLink(long linkId, String originUrl, LocalDateTime validDate, Integer enableStatus) {
        Link link = requireActiveLink(linkId);
        if (originUrl != null) {
            validateOrigin(originUrl);
            link.setOriginUrl(originUrl.trim());
        }
        link.setValidDate(validDate);
        if (enableStatus != null) {
            if (enableStatus != 0 && enableStatus != 1) {
                throw new BizException(ErrorCode.VALIDATION, "启用状态只能是 0 或 1");
            }
            link.setEnableStatus(enableStatus);
        }
        linkMapper.updateById(link);
        evict(link.getFullShortUrl());
        return link;
    }

    /** 普通短链移入回收站：禁止直接回收正在绑定活动的推广主短链。 */
    @Transactional
    public void recycle(long linkId) {
        var guard = activityGuards.getIfAvailable();
        if (guard != null) {
            guard.assertNotActivityMainLink(linkId);
        }
        Link link = requireActiveLink(linkId);
        link.setDelTime(System.currentTimeMillis());
        link.setDelFlag(1);
        linkMapper.updateById(link);
        gotoMapper.delete(Wrappers.<LinkGoto>lambdaQuery().eq(LinkGoto::getFullShortUrl, link.getFullShortUrl()));
        evict(link.getFullShortUrl());
    }

    @Transactional
    public Link restore(long linkId) {
        var merchant = merchantAccessService.requireMerchant();
        Link link = linkMapper.selectById(linkId);
        if (link == null || !merchant.getMerchantId().equals(link.getMerchantId()) || link.getDelTime() == null || link.getDelTime() == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "回收站中没有这条短链");
        }
        link.setDelTime(0L);
        link.setDelFlag(0);
        try {
            linkMapper.updateById(link);
        } catch (DataIntegrityViolationException exception) {
            throw new BizException(ErrorCode.CONFLICT, "该短链地址已被重新占用");
        }
        saveGoto(link);
        rememberBloom(link.getFullShortUrl());
        evict(link.getFullShortUrl());
        return link;
    }

    private LinkGroup defaultGroup() {
        long owner = merchantAccessService.requireMerchant().getMerchantId();
        String gid = "D" + owner;
        var query = Wrappers.<LinkGroup>lambdaQuery().eq(LinkGroup::getMerchantId, owner).eq(LinkGroup::getGid, gid);
        LinkGroup existing = groupMapper.selectOne(query);
        if (existing != null) return existing;
        LinkGroup group = new LinkGroup();
        group.setGid(gid); group.setMerchantId(owner); group.setName("默认分组"); group.setSortOrder(0); group.setStatus(1); group.setDelFlag(0);
        try { groupMapper.insert(group); return group; }
        catch (DataIntegrityViolationException exception) {
            existing = groupMapper.selectOne(query);
            if (existing != null) return existing;
            throw exception;
        }
    }

    private Link insertLink(LinkGroup group, String originUrl, LocalDateTime validDate, String base, String name, String customCode, Long activityId) {
        DataIntegrityViolationException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            String code = customCode == null ? newCode("", 6) : customCode;
            Link link = new Link();
            link.setMerchantId(group.getMerchantId());
            link.setActivityId(activityId);
            link.setGid(group.getGid());
            link.setName(name == null || name.isBlank() ? null : name.trim());
            link.setDomain(PublicLinkAddress.host(base));
            link.setShortUri(code);
            link.setFullShortUrl(PublicLinkAddress.full(base, code));
            link.setOriginUrl(originUrl);
            link.setValidDate(validDate);
            link.setEnableStatus(0);
            link.setDelTime(0L);
            link.setDelFlag(0);
            try {
                linkMapper.insert(link);
                saveGoto(link);
                rememberBloom(link.getFullShortUrl());
                evict(link.getFullShortUrl());
                return link;
            } catch (DataIntegrityViolationException exception) {
                if (customCode != null) throw new BizException(ErrorCode.CONFLICT, "这个短链后缀已被占用，请换一个");
                last = exception;
            }
        }
        throw new BizException(ErrorCode.CONFLICT, last == null ? "短链生成冲突" : "短链生成冲突");
    }

    private void saveGoto(Link link) {
        gotoMapper.delete(Wrappers.<LinkGoto>lambdaQuery().eq(LinkGoto::getFullShortUrl, link.getFullShortUrl()));
        LinkGoto row = new LinkGoto();
        row.setFullShortUrl(link.getFullShortUrl());
        row.setGid(link.getGid());
        gotoMapper.insert(row);
    }

    public Link findExisting(long linkId) {
        Link link = linkMapper.selectById(linkId);
        if (link == null || link.getDelTime() == null || link.getDelTime() != 0L) {
            return null;
        }
        return link;
    }

    public Link findOwned(long linkId) {
        return requireActiveLink(linkId);
    }

    public java.util.Map<Long, Link> findOwnedBatch(java.util.Collection<Long> linkIds) {
        if (linkIds == null || linkIds.isEmpty()) {
            return java.util.Map.of();
        }
        var merchant = merchantAccessService.requireMerchant();
        return linkMapper.selectBatchIds(linkIds).stream()
                .filter(link -> merchant.getMerchantId().equals(link.getMerchantId())
                        && link.getDelTime() != null && link.getDelTime() == 0L)
                .collect(java.util.stream.Collectors.toMap(Link::getId, link -> link, (a, b) -> a));
    }

    public Link findByShortUri(String shortUri) {
        if (shortUri == null || !shortUri.matches("[0-9A-Za-z]{2,12}")) {
            return null;
        }
        String domain = PublicLinkAddress.host(properties.getShortlink().getPublicBaseUrl());
        return linkMapper.selectOne(Wrappers.<Link>lambdaQuery()
                .eq(Link::getDomain, domain)
                .eq(Link::getShortUri, shortUri)
                .eq(Link::getDelTime, 0L));
    }

    public Link findActiveByShortUri(String shortUri) {
        if (shortUri == null || !shortUri.matches("[0-9A-Za-z]{2,12}")) {
            return null;
        }
        String domain = PublicLinkAddress.host(properties.getShortlink().getPublicBaseUrl());
        return linkMapper.selectOne(Wrappers.<Link>lambdaQuery()
                .eq(Link::getDomain, domain)
                .eq(Link::getShortUri, shortUri)
                .eq(Link::getDelTime, 0L)
                .eq(Link::getEnableStatus, 0));
    }

    public LinkGroup requireGroup(String gid) {
        var merchant = merchantAccessService.requireMerchant();
        LinkGroup group = groupMapper.selectOne(Wrappers.<LinkGroup>lambdaQuery().eq(LinkGroup::getGid, gid));
        if (group == null || !merchant.getMerchantId().equals(group.getMerchantId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "分组不存在");
        }
        return group;
    }

    public Link requireActiveLink(long linkId) {
        var merchant = merchantAccessService.requireMerchant();
        Link link = linkMapper.selectById(linkId);
        if (link == null || !merchant.getMerchantId().equals(link.getMerchantId()) || link.getDelTime() == null || link.getDelTime() != 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "短链不存在");
        }
        return link;
    }

    private void validateOrigin(String originUrl) {
        if (originUrl == null || originUrl.length() > 2048
                || !(originUrl.startsWith("https://") || originUrl.startsWith("http://"))) {
            throw new BizException(ErrorCode.VALIDATION, "原始链接必须以 http 或 https 开头");
        }
    }

    private void rememberBloom(String fullShortUrl) {
        RedissonClient client = redisson.getIfAvailable();
        if (client == null) {
            return;
        }
        RBloomFilter<String> bloom = client.getBloomFilter(ShortLinkRedisKeys.BLOOM_URI);
        if (bloom.isExists()) {
            bloom.add(fullShortUrl);
        }
    }

    private void evict(String fullShortUrl) {
        StringRedisTemplate template = redis.getIfAvailable();
        if (template == null) {
            return;
        }
        Runnable invalidate = () -> template.delete(List.of(
                ShortLinkRedisKeys.gotoCache(fullShortUrl), ShortLinkRedisKeys.gotoMiss(fullShortUrl)));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidate.run();
                }
            });
        } else {
            invalidate.run();
        }
    }

    private static String newCode(String prefix, int size) {
        StringBuilder builder = new StringBuilder(prefix);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < size; i++) {
            builder.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return builder.toString();
    }
}
