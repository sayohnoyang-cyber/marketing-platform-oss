package com.sayohno.marketing.campaign.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sayohno.marketing.campaign.dao.entity.CampaignOperation;
import com.sayohno.marketing.campaign.dao.mapper.CampaignOperationMapper;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.common.hash.RequestHashes;
import com.sayohno.marketing.common.result.ErrorCode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CampaignOperationStore {
    private final CampaignOperationMapper mapper;
    private final CampaignService campaigns;
    private final CampaignConfigurationService configuration;
    private final ObjectMapper json;

    public CampaignOperationStore(CampaignOperationMapper mapper, CampaignService campaigns, CampaignConfigurationService configuration, ObjectMapper json) {
        this.mapper = mapper; this.campaigns = campaigns; this.configuration = configuration; this.json = json;
    }

    public String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalArgumentException("无法保存执行数据", exception); }
    }

    public Map<String, Object> decode(CampaignOperation row) {
        try { return json.readValue(row.getPayloadJson(), new TypeReference<LinkedHashMap<String, Object>>() { }); }
        catch (Exception exception) { throw new IllegalStateException("执行记录无法读取", exception); }
    }

    public CampaignOperation existing(long id, String kind, String key, String hash) {
        campaigns.requireOwned(id);
        if (key == null || key.isBlank() || key.length() > 64) throw new BizException(ErrorCode.VALIDATION, "请求标识不合法");
        var row = mapper.selectOne(Wrappers.<CampaignOperation>lambdaQuery().eq(CampaignOperation::getActivityId, id)
                .eq(CampaignOperation::getKind, kind).eq(CampaignOperation::getRequestKey, key));
        if (row != null && !row.getRequestHash().equals(hash)) throw new BizException(ErrorCode.CONFLICT, "同一请求不能更改参数或活动配置");
        return row;
    }

    public CampaignOperation create(long activityId, String kind, String key, String hash, String configurationHash, String status, Object data) {
        var activity = campaigns.requireOwned(activityId);
        CampaignOperation row = new CampaignOperation();
        row.setActivityId(activityId); row.setMerchantId(activity.getMerchantId()); row.setKind(kind); row.setRequestKey(key);
        row.setRequestHash(hash); row.setConfigurationHash(configurationHash); row.setStatus(status); row.setPayloadJson(encode(data));
        try { mapper.insert(row); }
        catch (DataIntegrityViolationException exception) { throw new BizException(ErrorCode.CONFLICT, "该请求正在处理，请使用原请求标识重试"); }
        return row;
    }

    public void update(CampaignOperation row, String status, Object data) {
        row.setStatus(status); row.setPayloadJson(encode(data));
        if ("PREPARE".equals(row.getKind()) && row.getExecutionToken() != null) {
            int updated = mapper.update(row, Wrappers.<CampaignOperation>lambdaUpdate().eq(CampaignOperation::getId, row.getId())
                    .eq(CampaignOperation::getExecutionToken, row.getExecutionToken()).eq(CampaignOperation::getStatus, "RUNNING"));
            if (updated != 1) throw new BizException(ErrorCode.CONFLICT, "执行权已转移，请重新读取检查结果");
        } else mapper.updateById(row);
    }

    public CampaignOperation require(long activityId, long operationId, String kind) {
        campaigns.requireOwned(activityId);
        var row = mapper.selectById(operationId);
        if (row == null || row.getActivityId() != activityId || !kind.equals(row.getKind())) throw new BizException(ErrorCode.NOT_FOUND, "执行记录不存在");
        return row;
    }

    public List<Map<String, Object>> list(long activityId, String kind) {
        campaigns.requireOwned(activityId);
        String current = configuration.fingerprint(activityId);
        return mapper.selectList(Wrappers.<CampaignOperation>lambdaQuery().eq(CampaignOperation::getActivityId, activityId)
                .eq(CampaignOperation::getKind, kind).orderByDesc(CampaignOperation::getCreateTime).orderByDesc(CampaignOperation::getId)
                .last("LIMIT 20")).stream().map(row -> present(row, current)).toList();
    }

    public Map<String, Object> present(CampaignOperation row) { return present(row, configuration.fingerprint(row.getActivityId())); }

    private Map<String, Object> present(CampaignOperation row, String current) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", row.getId()); result.put("kind", row.getKind()); result.put("status", row.getStatus());
        result.put("stale", !row.getConfigurationHash().equals(current)); result.put("configurationHash", row.getConfigurationHash());
        result.put("createdAt", row.getCreateTime()); result.put("updatedAt", row.getUpdateTime()); result.put("data", decode(row));
        return result;
    }

    public String hash(Object data, String configurationHash) { return RequestHashes.sha256(encode(data) + "|" + configurationHash); }
}
