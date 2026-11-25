package com.sayohno.marketing.coupon.service;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sayohno.marketing.common.config.MarketingProperties;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.common.result.ErrorCode;
import com.sayohno.marketing.coupon.dao.entity.ClaimTicket;
import com.sayohno.marketing.coupon.dao.entity.GrantBatch;
import com.sayohno.marketing.coupon.dao.entity.GrantBatchError;
import com.sayohno.marketing.coupon.dao.entity.OfferPlan;
import com.sayohno.marketing.coupon.dao.mapper.ClaimTicketMapper;
import com.sayohno.marketing.coupon.dao.mapper.GrantBatchErrorMapper;
import com.sayohno.marketing.coupon.dao.mapper.GrantBatchMapper;
import com.sayohno.marketing.coupon.enums.ClaimTicketStatus;
import com.sayohno.marketing.coupon.enums.GrantBatchStatus;
import com.sayohno.marketing.coupon.enums.IssuerScope;
import com.sayohno.marketing.identity.IamStatus;
import com.sayohno.marketing.identity.dao.entity.IamUser;
import com.sayohno.marketing.identity.dao.mapper.IamUserMapper;
import com.sayohno.marketing.identity.service.MerchantAccessService;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@Service
public class GrantBatchService {
    private static final Logger log = LoggerFactory.getLogger(GrantBatchService.class);
    private final GrantBatchMapper taskMapper;
    private final GrantBatchErrorMapper failMapper;
    private final ClaimTicketMapper claimRequestMapper;
    private final OfferPlanService templateService;
    private final VoucherClaimService claimService;
    private final MerchantAccessService merchantAccessService;
    private final IamUserMapper iamUserMapper;
    private final MarketingProperties properties;

    public GrantBatchService(GrantBatchMapper taskMapper,
                             GrantBatchErrorMapper failMapper,
                             ClaimTicketMapper claimRequestMapper,
                             OfferPlanService templateService,
                             VoucherClaimService claimService,
                             MerchantAccessService merchantAccessService,
                             IamUserMapper iamUserMapper,
                             MarketingProperties properties) {
        this.taskMapper = taskMapper;
        this.failMapper = failMapper;
        this.claimRequestMapper = claimRequestMapper;
        this.templateService = templateService;
        this.claimService = claimService;
        this.merchantAccessService = merchantAccessService;
        this.iamUserMapper = iamUserMapper;
        this.properties = properties;
    }

    public GrantBatch create(long templateId, byte[] file, String originalName) {
        return create(templateId, file, originalName, null);
    }

    public GrantBatch create(long templateId, byte[] file, String originalName, LocalDateTime runAt) {
        var merchant = merchantAccessService.requireMerchant();
        OfferPlan template = templateService.requireOwned(templateId);
        if (file == null || file.length == 0) {
            throw new BizException(ErrorCode.VALIDATION, "发券文件不能为空");
        }
        List<BatchUserRow> rows = read(file);
        String batchCode = UUID.randomUUID().toString().replace("-", "");
        Path path = taskDir().resolve(batchCode + "-" + safeName(originalName));
        try {
            Files.write(path, file);
        } catch (Exception exception) {
            throw new BizException(ErrorCode.SYSTEM, "发券文件保存失败");
        }
        GrantBatch task = new GrantBatch();
        task.setMerchantId(merchant.getMerchantId());
        task.setPlanId(template.getId());
        task.setBatchCode(batchCode);
        task.setFilePath(path.toString());
        task.setRunAt(runAt == null ? LocalDateTime.now() : runAt);
        task.setStatus(GrantBatchStatus.QUEUED);
        task.setProgress(0);
        task.setTotalCount(rows.size());
        task.setSuccessCount(0);
        task.setFailCount(0);
        taskMapper.insert(task);
        return task;
    }

    public List<GrantBatch> list() {
        var merchant = merchantAccessService.requireMerchant();
        return taskMapper.selectList(Wrappers.<GrantBatch>lambdaQuery()
                .eq(GrantBatch::getMerchantId, merchant.getMerchantId())
                .orderByDesc(GrantBatch::getId));
    }

    public GrantBatch requireOwned(long taskId) {
        var merchant = merchantAccessService.requireMerchant();
        GrantBatch task = taskMapper.selectById(taskId);
        if (task == null || !merchant.getMerchantId().equals(task.getMerchantId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "发券任务不存在");
        }
        return task;
    }

    public byte[] failureFile(long taskId) {
        GrantBatch task = requireOwned(taskId);
        List<GrantBatchError> fails = failMapper.selectList(Wrappers.<GrantBatchError>lambdaQuery()
                .eq(GrantBatchError::getBatchId, task.getId())
                .orderByAsc(GrantBatchError::getRowNo));
        StringBuilder csv = new StringBuilder("行号,用户ID,原因\n");
        for (GrantBatchError fail : fails) {
            csv.append(fail.getRowNo()).append(',')
                    .append(fail.getUserId() == null ? "" : fail.getUserId()).append(',')
                    .append(fail.getReason().replace(',', ' ')).append('\n');
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    public int scanDue() {
        return scanDue(null);
    }

    /**
     * 扫描并异步派发到期发券任务到独立线程池，避免大批量 Excel 执行阻塞定时调度线程。
     */
    public int scanDueAsync(Executor executor) {
        return scanDue(executor);
    }

    private int scanDue(Executor executor) {
        LocalDateTime now = LocalDateTime.now();
        List<GrantBatch> tasks = taskMapper.selectList(Wrappers.<GrantBatch>lambdaQuery()
                .in(GrantBatch::getStatus, GrantBatchStatus.QUEUED, GrantBatchStatus.RUNNING)
                .and(query -> query.isNull(GrantBatch::getRunAt).or().le(GrantBatch::getRunAt, now)));
        int started = 0;
        for (GrantBatch task : tasks) {
            if (task.getStatus() == GrantBatchStatus.RUNNING && task.getLeaseUntil() != null && task.getLeaseUntil().isAfter(now)) {
                continue;
            }
            String token = UUID.randomUUID().toString().replace("-", "");
            if (taskMapper.acquire(task.getId(), token, now.plus(properties.getOffer().getGrantBatchLease()), now) == 1) {
                if (executor == null) {
                    execute(task.getId(), token);
                    started++;
                } else {
                    try {
                        executor.execute(() -> execute(task.getId(), token));
                        started++;
                    } catch (RejectedExecutionException exception) {
                        log.warn("批量发券线程池已满，任务 {} 等待租约到期后由下一轮调度接管", task.getId());
                    }
                }
            }
        }
        return started;
    }

    public void execute(long taskId, String token) {
        GrantBatch task = taskMapper.selectById(taskId);
        if (task == null || !token.equals(task.getOwnerToken())) {
            return;
        }
        List<BatchUserRow> rows;
        try {
            rows = read(Files.readAllBytes(Path.of(task.getFilePath())));
        } catch (Exception exception) {
            finish(task, token, GrantBatchStatus.FAILED);
            return;
        }
        for (int index = 0; index < rows.size(); index++) {
            if (!owns(taskId, token)) {
                return;
            }
            if (!properties.getOffer().getGrantBatchRowDelay().isZero()) {
                try {
                    Thread.sleep(properties.getOffer().getGrantBatchRowDelay().toMillis());
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            if (!owns(taskId, token)) {
                return;
            }
            int rowNo = index + 2;
            String raw = rows.get(index).getUserId();
            Long userId = parseUser(raw);
            if (userId == null) {
                if (!recordFail(taskId, token, rowNo, null, "用户编号无效")) {
                    return;
                }
                refreshCounts(taskId, token, rows.size());
                continue;
            }
            IamUser account = iamUserMapper.selectById(userId);
            if (account == null || account.getStatus() == null || account.getStatus() != IamStatus.ENABLED) {
                if (!recordFail(taskId, token, rowNo, userId, "用户不存在或已停用")) {
                    return;
                }
                refreshCounts(taskId, token, rows.size());
                continue;
            }
            try {
                claimService.claim(userId, task.getPlanId(), IssuerScope.PLATFORM, "b:" + taskId + ":" + rowNo);
                if (failMapper.deleteOwned(taskId, rowNo, token) < 0) {
                    return;
                }
            } catch (BizException exception) {
                if (!recordFail(taskId, token, rowNo, userId, exception.getMessage())) {
                    return;
                }
            }
            if (!refreshCounts(taskId, token, rows.size())) {
                return;
            }
        }
        GrantBatch latest = taskMapper.selectById(taskId);
        if (latest == null || !token.equals(latest.getOwnerToken())) {
            return;
        }
        GrantBatchStatus status = latest.getFailCount() == 0
                ? GrantBatchStatus.DONE
                : (latest.getSuccessCount() > 0 ? GrantBatchStatus.PARTIAL : GrantBatchStatus.FAILED);
        finish(latest, token, status);
    }

    private boolean owns(long taskId, String token) {
        return taskMapper.renew(taskId, token, LocalDateTime.now().plus(properties.getOffer().getGrantBatchLease()), LocalDateTime.now()) == 1;
    }

    private boolean refreshCounts(long taskId, String token, int total) {
        String prefix = "b:" + taskId + ":";
        Long success = claimRequestMapper.selectCount(Wrappers.<ClaimTicket>lambdaQuery()
                .eq(ClaimTicket::getStatus, ClaimTicketStatus.SUCCEEDED)
                .likeRight(ClaimTicket::getRequestId, prefix));
        Long failed = failMapper.selectCount(Wrappers.<GrantBatchError>lambdaQuery().eq(GrantBatchError::getBatchId, taskId));
        int successCount = success == null ? 0 : success.intValue();
        int failCount = failed == null ? 0 : failed.intValue();
        int progress = total == 0 ? 100 : Math.min(100, (successCount + failCount) * 100 / total);
        return taskMapper.saveProgress(taskId, token, total, successCount, failCount, progress, LocalDateTime.now()) == 1;
    }

    private void finish(GrantBatch task, String token, GrantBatchStatus status) {
        taskMapper.finishOwned(task.getId(), token, status, LocalDateTime.now());
    }

    private boolean recordFail(long taskId, String token, int rowNo, Long userId, String reason) {
        GrantBatchError existing = failMapper.selectOne(Wrappers.<GrantBatchError>lambdaQuery()
                .eq(GrantBatchError::getBatchId, taskId)
                .eq(GrantBatchError::getRowNo, rowNo));
        if (existing != null) {
            return owns(taskId, token);
        }
        return failMapper.insertOwned(IdWorker.getId(), taskId, rowNo, userId,
                reason == null ? "未知失败" : reason, token, LocalDateTime.now()) == 1;
    }

    private List<BatchUserRow> read(byte[] file) {
        try {
            return EasyExcel.read(new ByteArrayInputStream(file)).head(BatchUserRow.class).sheet().doReadSync();
        } catch (Exception exception) {
            throw new BizException(ErrorCode.VALIDATION, "无法解析发券文件");
        }
    }

    private Long parseUser(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Path taskDir() {
        String configured = properties.getOffer().getGrantBatchDir();
        Path path = (configured == null || configured.isBlank())
                ? Path.of(System.getProperty("java.io.tmpdir"), "marketing-grant-batches")
                : Path.of(configured);
        try {
            Files.createDirectories(path);
        } catch (Exception exception) {
            throw new BizException(ErrorCode.SYSTEM, "发券目录不可用");
        }
        return path;
    }

    private String safeName(String originalName) {
        String name = originalName == null ? "users.xlsx" : originalName;
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
