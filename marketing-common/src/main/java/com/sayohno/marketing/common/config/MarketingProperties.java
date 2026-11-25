package com.sayohno.marketing.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Data
@ConfigurationProperties(prefix = "marketing")
public class MarketingProperties {
    private final Redis redis = new Redis();
    private final Session session = new Session();
    private final Mq mq = new Mq();
    private final Web web = new Web();
    private final Shortlink shortlink = new Shortlink();
    private final Outbox outbox = new Outbox();
    private final Offer offer = new Offer();
    private final Capacity capacity = new Capacity();

    @Data
    public static class Capacity {
        /** Change whenever instance count, hardware or limits change. */
        private String deploymentId = "local-single";
        /** 没有有效限流策略时每场活动领券的默认每秒额度，0 表示不启用默认保护。 */
        private int defaultClaimQps = 200;
        /** 单用户每秒允许的最大领券请求次数，0 表示关闭单用户频控。 */
        private int perUserClaimQps = 2;
        /** 限流判定在本机的缓存时长，策略应用或停用时立即失效。 */
        private Duration policyCacheTtl = Duration.ofSeconds(3);
        /** 预测中未预约有效访客转化为开场 10 秒到场的参考系数。 */
        private double unreservedArrivalRate = 0.2D;
        /** 是否开启开场前 5 分钟自动预热调度兜底。 */
        private boolean autoPrepareEnabled = false;
    }

    @Data
    public static class Redis {
        private boolean enabled = true;
    }

    @Data
    public static class Session {
        private Duration ttl = Duration.ofMinutes(30);
        /** 单个登录会话允许滑动续期的最长总时限，防止脚本轮询导致会话无限期有效。 */
        private Duration maxLifetime = Duration.ofHours(12);
        /** 用户上下文在本机的短缓存时长，默认 0 表示每次实时校验账号与成员状态；大促高峰可按需设为数秒。 */
        private Duration contextCacheTtl = Duration.ZERO;
    }

    @Data
    public static class Mq {
        private boolean enabled = false;
        private String nameServer = "127.0.0.1:9876";
        private String topic = "marketing-event";
    }

    @Data
    public static class Web {
        private List<String> corsAllowedOrigins = new ArrayList<>(List.of(
                "http://127.0.0.1:5173",
                "http://localhost:5173",
                "http://127.0.0.1:5174",
                "http://localhost:5174"
        ));
        private String consoleBaseUrl = "http://127.0.0.1:5173";
    }

    @Data
    public static class Shortlink {
        /** 布隆过滤器默认预期容量（100 万，兼顾常见中小规模部署的 Redis 内存占用）。 */
        private long bloomExpectedInsertions = 1_000_000L;
        private double bloomFalseProbability = 0.001D;
        private String publicBaseUrl = "http://127.0.0.1:9100";
        private boolean trustForwarded = false;
        /** 302 跳转是否异步入队批量写入访问统计，避免高并发跳转热路径同步阻塞写库。 */
        private boolean asyncVisitRecord = true;
    }

    @Data
    public static class Outbox {
        private boolean publishEnabled = true;
        private int maxRetries = 8;
        private Duration retryDelay = Duration.ofSeconds(5);
        private Duration sendingLease = Duration.ofSeconds(30);
        private int batchSize = 200;
        private int maxPerCycle = 2000;
        private Duration retention = Duration.ofDays(7);
        private Duration visitRetention = Duration.ofDays(95);
    }

    @Data
    public static class Offer {
        private boolean grantBatchScanEnabled = true;
        private Duration grantBatchLease = Duration.ofMinutes(2);
        private Duration grantBatchRowDelay = Duration.ZERO;
        private String grantBatchDir = "";
        private Duration claimRecoverAfter = Duration.ofSeconds(15);
    }
}
