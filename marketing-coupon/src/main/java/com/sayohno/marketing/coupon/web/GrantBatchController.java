package com.sayohno.marketing.coupon.web;

import com.sayohno.marketing.common.result.Result;
import com.sayohno.marketing.common.result.Results;
import com.sayohno.marketing.coupon.dao.entity.GrantBatch;
import com.sayohno.marketing.coupon.service.GrantBatchService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/merchant/grant-batches")
public class GrantBatchController {
    private final GrantBatchService taskService;

    public GrantBatchController(GrantBatchService taskService) {
        this.taskService = taskService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Map<String, Object>> create(@RequestParam long planId,
                                              @RequestParam("file") MultipartFile file,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime runAt) throws Exception {
        GrantBatch task = taskService.create(planId, file.getBytes(), file.getOriginalFilename(), runAt);
        return Results.success(view(task));
    }

    @GetMapping
    public Result<?> list() {
        return Results.success(taskService.list().stream().map(this::view).toList());
    }

    @GetMapping("/{id}")
    public Result<Map<String, Object>> detail(@PathVariable long id) {
        return Results.success(view(taskService.requireOwned(id)));
    }

    @GetMapping("/{id}/failures")
    public ResponseEntity<byte[]> failures(@PathVariable long id) {
        byte[] body = taskService.failureFile(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=task-" + id + "-failures.csv")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(body);
    }

    private Map<String, Object> view(GrantBatch task) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", task.getId());
        view.put("batchCode", task.getBatchCode());
        view.put("planId", task.getPlanId());
        view.put("status", task.getStatus());
        view.put("progress", task.getProgress());
        view.put("totalCount", task.getTotalCount());
        view.put("successCount", task.getSuccessCount());
        view.put("failCount", task.getFailCount());
        view.put("runAt", task.getRunAt());
        view.put("notificationSent", false);
        view.put("notificationMessage", "外部通知未接入");
        return view;
    }
}
