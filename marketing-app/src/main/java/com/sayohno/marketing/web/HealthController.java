package com.sayohno.marketing.web;

import com.sayohno.marketing.common.config.MarketingProperties;
import com.sayohno.marketing.common.outbox.OutboxEventMapper;
import com.sayohno.marketing.common.result.ErrorCode;
import com.sayohno.marketing.common.result.Result;
import com.sayohno.marketing.common.result.Results;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 业务健康检查接口：真实探活 MySQL、Redis（启用时）并汇报 Outbox 积压/死信摘要；
 * 当核心依赖不可用时返回 HTTP 503 与具体组件故障细节，防止假绿接入流量。
 */
@RestController
public class HealthController {
    private final DataSource dataSource;
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final OutboxEventMapper outboxMapper;
    private final MarketingProperties properties;

    public HealthController(DataSource dataSource,
                            ObjectProvider<StringRedisTemplate> redisProvider,
                            OutboxEventMapper outboxMapper,
                            MarketingProperties properties) {
        this.dataSource = dataSource;
        this.redisProvider = redisProvider;
        this.outboxMapper = outboxMapper;
        this.properties = properties;
    }

    @GetMapping("/api/health")
    public ResponseEntity<Result<Map<String, Object>>> health() {
        Map<String, Object> components = new LinkedHashMap<>();
        boolean healthy = true;

        Map<String, Object> db = probeDatabase();
        components.put("db", db);
        if (!"UP".equals(db.get("status"))) {
            healthy = false;
        }

        Map<String, Object> redis = probeRedis();
        components.put("redis", redis);
        if ("DOWN".equals(redis.get("status"))) {
            healthy = false;
        }

        components.put("outbox", probeOutbox());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", healthy ? "UP" : "DOWN");
        data.put("components", components);

        if (healthy) {
            return ResponseEntity.ok(Results.success(data));
        }
        Result<Map<String, Object>> failure = Results.failure(ErrorCode.SYSTEM, "关键依赖探活失败");
        failure.setData(data);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(failure);
    }

    private Map<String, Object> probeDatabase() {
        Map<String, Object> status = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
            status.put("status", "UP");
        } catch (Exception exception) {
            status.put("status", "DOWN");
            status.put("error", safeMessage(exception));
        }
        return status;
    }

    private Map<String, Object> probeRedis() {
        Map<String, Object> status = new LinkedHashMap<>();
        if (!properties.getRedis().isEnabled()) {
            status.put("status", "SKIPPED");
            return status;
        }
        StringRedisTemplate redis = redisProvider == null ? null : redisProvider.getIfAvailable();
        if (redis == null) {
            status.put("status", "DOWN");
            status.put("error", "StringRedisTemplate 未装配");
            return status;
        }
        try {
            String pong = redis.execute((RedisCallback<String>) connection -> connection.ping());
            if ("PONG".equalsIgnoreCase(pong)) {
                status.put("status", "UP");
            } else {
                status.put("status", "DOWN");
                status.put("error", "Redis PING 响应异常: " + pong);
            }
        } catch (Exception exception) {
            status.put("status", "DOWN");
            status.put("error", safeMessage(exception));
        }
        return status;
    }

    private Map<String, Object> probeOutbox() {
        Map<String, Object> status = new LinkedHashMap<>();
        try {
            status.put("status", "UP");
            status.put("pending", outboxMapper.countPending());
            status.put("dead", outboxMapper.countDead());
        } catch (Exception exception) {
            status.put("status", "UNKNOWN");
            status.put("error", safeMessage(exception));
        }
        return status;
    }

    private static String safeMessage(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
