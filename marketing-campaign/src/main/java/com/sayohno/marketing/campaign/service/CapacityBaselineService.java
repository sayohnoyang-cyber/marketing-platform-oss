package com.sayohno.marketing.campaign.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sayohno.marketing.campaign.dao.entity.CapacityBaseline;
import com.sayohno.marketing.campaign.dao.mapper.CapacityBaselineMapper;
import com.sayohno.marketing.campaign.web.dto.CapacityRequest;
import com.sayohno.marketing.common.config.MarketingProperties;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.common.result.ErrorCode;
import com.sayohno.marketing.identity.service.MerchantAccessService;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CapacityBaselineService {
    private final CapacityBaselineMapper mapper;
    private final MerchantAccessService access;
    private final MarketingProperties properties;
    private final Clock clock;

    public CapacityBaselineService(CapacityBaselineMapper mapper, MerchantAccessService access, MarketingProperties properties, Clock clock) {
        this.mapper = mapper; this.access = access; this.properties = properties; this.clock = clock;
    }

    public Map<String, Object> create(CapacityRequest request) {
        long merchantId = access.requireMerchant().getMerchantId();
        LocalDateTime now = LocalDateTime.now(clock);
        if (request.measuredAt().isAfter(now.plusMinutes(1)) || !request.expiresAt().isAfter(now)
                || !request.expiresAt().isAfter(request.measuredAt())
                || Duration.between(request.measuredAt(), request.expiresAt()).compareTo(Duration.ofDays(7)) > 0
                || request.p99Millis() < request.p95Millis()
                || request.sampleCount() < request.sustainedQps() * (long) request.durationSeconds() * .9) {
            throw new BizException(ErrorCode.VALIDATION, "压测时间、有效期、延迟或样本数量不合法");
        }
        CapacityBaseline row = new CapacityBaseline();
        row.setMerchantId(merchantId); row.setDeploymentId(request.deploymentId()); row.setReportName(request.reportName());
        row.setEndpoint("coupon-claim"); row.setSustainedQps(request.sustainedQps()); row.setP95Millis(request.p95Millis());
        row.setP99Millis(request.p99Millis()); row.setErrorRate(request.errorRate()); row.setDurationSeconds(request.durationSeconds());
        row.setSampleCount(request.sampleCount()); row.setMeasuredAt(request.measuredAt()); row.setExpiresAt(request.expiresAt());
        mapper.insert(row);
        return present(row);
    }

    public Map<String, Object> list() {
        long merchantId = access.requireMerchant().getMerchantId();
        return Map.of("deploymentId", properties.getCapacity().getDeploymentId(), "records",
                mapper.selectList(Wrappers.<CapacityBaseline>lambdaQuery().eq(CapacityBaseline::getMerchantId, merchantId)
                        .orderByDesc(CapacityBaseline::getCreateTime).last("LIMIT 50")).stream().map(this::present).toList());
    }

    public CapacityBaseline requireUsable(Long id) {
        if (id == null) throw new BizException(ErrorCode.VALIDATION, "请选择真实压测基线");
        CapacityBaseline row = mapper.selectById(id);
        if (row == null || !row.getMerchantId().equals(access.requireMerchant().getMerchantId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "容量基线不存在");
        }
        if (!usable(row)) throw new BizException(ErrorCode.CONFLICT, "容量基线已过期或不属于当前部署");
        return row;
    }

    public boolean usable(CapacityBaseline row) {
        return row.getDeploymentId().equals(properties.getCapacity().getDeploymentId())
                && row.getExpiresAt().isAfter(LocalDateTime.now(clock));
    }

    public boolean hasUsableBaseline(long merchantId) {
        Long count = mapper.selectCount(Wrappers.<CapacityBaseline>lambdaQuery()
                .eq(CapacityBaseline::getMerchantId, merchantId)
                .eq(CapacityBaseline::getDeploymentId, properties.getCapacity().getDeploymentId())
                .gt(CapacityBaseline::getExpiresAt, LocalDateTime.now(clock)));
        return count != null && count > 0;
    }

    public int safeQps(CapacityBaseline row) { return Math.max(1, (int) Math.floor(row.getSustainedQps() * .8)); }

    private Map<String, Object> present(CapacityBaseline row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.getId()); result.put("reportName", row.getReportName()); result.put("deploymentId", row.getDeploymentId());
        result.put("measuredAt", row.getMeasuredAt()); result.put("expiresAt", row.getExpiresAt()); result.put("p99Millis", row.getP99Millis());
        result.put("sustainedQps", row.getSustainedQps()); result.put("safeQps", safeQps(row)); result.put("usable", usable(row));
        result.put("source", "运营录入的压测报告；系统检查字段与有效期，不独立证明报告真实性");
        return result;
    }
}
