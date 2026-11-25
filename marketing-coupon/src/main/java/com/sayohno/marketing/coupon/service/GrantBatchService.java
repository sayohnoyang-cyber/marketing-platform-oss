package com.sayohno.marketing.coupon.service;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sayohno.marketing.common.config.MarketingProperties;
import com.sayohno.marketing.common.exception.BizException;
import com.sayohno.marketing.common.result.ErrorCode;
import com.sayohno.marketing.coupon.dao.entity.GrantBatch;
import com.sayohno.marketing.coupon.dao.entity.GrantBatchError;
import com.sayohno.marketing.coupon.dao.entity.OfferPlan;
import com.sayohno.marketing.coupon.dao.mapper.GrantBatchErrorMapper;
import com.sayohno.marketing.coupon.dao.mapper.GrantBatchMapper;
import com.sayohno.marketing.coupon.enums.GrantBatchStatus;
import com.sayohno.marketing.coupon.enums.IssuerScope;
import com.sayohno.marketing.identity.service.IdentityAccountService;
import com.sayohno.marketing.identity.service.MerchantAccessService;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class GrantBatchService {
    private static final Logger log = LoggerFactory.getLogger(GrantBatchService.class);
    /** 批量更新进度与续租的步长，避免每行都查一次全表 COUNT(*) 造成 O(N²) 查询风暴。 */
    private static final int PROGRESS_FLUSH_INTERVAL = 50;

    private final GrantBatchMapper taskMapper;
    private final GrantBatchErrorMapper failMapper;
    private final OfferPlanService templateService;
    private final VoucherClaimService claimService;
    private final MerchantAccessService merchantAccessService;
    private final IdentityAccountService accountService;
    private final MarketingProperties properties;

    public GrantBatchService(GrantBatchMapper taskMapper,
                             GrantBatchErrorMapper failMapper,
                             OfferPlanService templateService,
                             VoucherClaimService claimService,
                             MerchantAccessService merchantAccessService,
                             IdentityAccountService accountService,
                             MarketingProperties properties) {
        this.taskMapper = taskMapper;
        this.failMapper = failMapper;
        this.templateService = templateService;
        this.claimService = claimService;
        this.merchantAccessService = merchantAccessService;
        this.accountService = accountService;
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
        int totalRows = countRowsStreaming(file);
        String batchCode = UUID.randomUUID().toString().replace("-", "");
        GrantBatch task = new GrantBatch();
        task.setMerchantId(merchant.getMerchantId());
        task.setPlanId(template.getId());
        task.setBatchCode(batchCode);
        // 原始 Excel 字节持久化到共享数据库，彻底脱离对单机本地磁盘的依赖，支持多实例无状态抢占执行。
        task.setFilePath("db://" + batchCode + "-" + safeName(originalName));
        task.setFileContent(file);
        task.setRunAt(runAt == null ? LocalDateTime.now() : runAt);
        task.setStatus(GrantBatchStatus.QUEUED);
        task.setProgress(0);
        task.setTotalCount(totalRows);
        task.setSuccessCount(0);
        task.setFailCount(0);
        taskMapper.insert(task);
        return task;
    }

    public List<GrantBatch> list() {
        var merchant = merchantAccessService.requireMerchant();
        // 列表查询排除大字段 file_content，避免返回批次列表时拉取大量 Excel 二进制载荷。
        return taskMapper.selectList(Wrappers.<GrantBatch>lambdaQuery()
                .select(GrantBatch.class, info -> !"file_content".equals(info.getColumn()))
                .eq(GrantBatch::getMerchantId, merchant.getMerchantId())
                .orderByDesc(GrantBatch::getId));
    }

    public GrantBatch requireOwned(long taskId) {
        var merchant = merchantAccessService.requireMerchant();
        GrantBatch task = taskMapper.selectOne(Wrappers.<GrantBatch>lambdaQuery()
                .select(GrantBatch.class, info -> !"file_content".equals(info.getColumn()))
                .eq(GrantBatch::getId, taskId));
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
        // 扫描调度仅需状态与租约列，不拉取 file_content。
        List<GrantBatch> tasks = taskMapper.selectList(Wrappers.<GrantBatch>lambdaQuery()
                .select(GrantBatch.class, info -> !"file_content".equals(info.getColumn()))
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
        byte[] payload = loadPayload(task);
        if (payload == null) {
            finish(task, token, GrantBatchStatus.FAILED);
            return;
        }
        int expectedTotal = task.getTotalCount() == null ? 0 : task.getTotalCount();
        AtomicInteger processed = new AtomicInteger();
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();
        AtomicBoolean aborted = new AtomicBoolean(false);
        boolean perRowRenew = !properties.getOffer().getGrantBatchRowDelay().isZero();

        try {
            // 流式逐行读取并处理，避免 doReadSync() 一次性把整张表驻留内存，同时在内存累计成功/失败数消除 O(N²) 进度查询。
            EasyExcel.read(new ByteArrayInputStream(payload), BatchUserRow.class, new ReadListener<BatchUserRow>() {
                @Override
                public void invoke(BatchUserRow data, AnalysisContext context) {
                    if (aborted.get()) {
                        return;
                    }
                    int idx = processed.get();
                    if ((perRowRenew || idx % PROGRESS_FLUSH_INTERVAL == 0) && !owns(taskId, token)) {
                        aborted.set(true);
                        return;
                    }
                    if (perRowRenew) {
                        try {
                            Thread.sleep(properties.getOffer().getGrantBatchRowDelay().toMillis());
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            aborted.set(true);
                            return;
                        }
                        if (!owns(taskId, token)) {
                            aborted.set(true);
                            return;
                        }
                    }
                    int rowNo = idx + 2;
                    Long userId = parseUser(data == null ? null : data.getUserId());
                    if (userId == null) {
                        if (!recordFail(taskId, token, rowNo, null, "用户编号无效")) {
                            aborted.set(true);
                            return;
                        }
                        failCount.incrementAndGet();
                    } else if (!accountService.isUserEnabled(userId)) {
                        if (!recordFail(taskId, token, rowNo, userId, "用户不存在或已停用")) {
                            aborted.set(true);
                            return;
                        }
                        failCount.incrementAndGet();
                    } else {
                        try {
                            claimService.claim(userId, task.getPlanId(), IssuerScope.PLATFORM, "b:" + taskId + ":" + rowNo);
                            if (failMapper.deleteOwned(taskId, rowNo, token) < 0) {
                                aborted.set(true);
                                return;
                            }
                            successCount.incrementAndGet();
                        } catch (BizException exception) {
                            if (!recordFail(taskId, token, rowNo, userId, exception.getMessage())) {
                                aborted.set(true);
                                return;
                            }
                            failCount.incrementAndGet();
                        }
                    }
                    int doneRows = processed.incrementAndGet();
                    if (perRowRenew || doneRows % PROGRESS_FLUSH_INTERVAL == 0) {
                        int total = Math.max(expectedTotal, doneRows);
                        if (!flushProgress(taskId, token, total, successCount.get(), failCount.get())) {
                            aborted.set(true);
                        }
                    }
                }

                @Override
                public void doAfterAllAnalysed(AnalysisContext context) {
                }
            }).sheet().doRead();
        } catch (Exception exception) {
            finish(task, token, GrantBatchStatus.FAILED);
            return;
        }

        if (aborted.get()) {
            return;
        }
        int total = Math.max(expectedTotal, processed.get());
        if (!flushProgress(taskId, token, total, successCount.get(), failCount.get())) {
            return;
        }
        GrantBatchStatus status = failCount.get() == 0
                ? GrantBatchStatus.DONE
                : (successCount.get() > 0 ? GrantBatchStatus.PARTIAL : GrantBatchStatus.FAILED);
        finish(task, token, status);
    }

    private byte[] loadPayload(GrantBatch task) {
        if (task.getFileContent() != null && task.getFileContent().length > 0) {
            return task.getFileContent();
        }
        // 兼容历史未迁移到数据库载荷的记录与本地文件路径测试构造
        try {
            return Files.readAllBytes(Path.of(task.getFilePath()));
        } catch (Exception exception) {
            return null;
        }
    }

    private boolean owns(long taskId, String token) {
        return taskMapper.renew(taskId, token, LocalDateTime.now().plus(properties.getOffer().getGrantBatchLease()), LocalDateTime.now()) == 1;
    }

    private boolean flushProgress(long taskId, String token, int total, int successCount, int failCount) {
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

    private int countRowsStreaming(byte[] file) {
        AtomicInteger count = new AtomicInteger();
        try {
            EasyExcel.read(new ByteArrayInputStream(file), BatchUserRow.class, new ReadListener<BatchUserRow>() {
                @Override
                public void invoke(BatchUserRow data, AnalysisContext context) {
                    count.incrementAndGet();
                }

                @Override
                public void doAfterAllAnalysed(AnalysisContext context) {
                }
            }).sheet().doRead();
            return count.get();
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

    private String safeName(String originalName) {
        String name = originalName == null ? "users.xlsx" : originalName;
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
