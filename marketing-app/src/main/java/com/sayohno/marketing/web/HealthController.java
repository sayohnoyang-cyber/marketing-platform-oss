package com.sayohno.marketing.web;

import com.sayohno.marketing.common.result.Result;
import com.sayohno.marketing.common.result.Results;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/health")
    public Result<Map<String, String>> health() {
        // LIMIT 0 只校验业务表和列是否可读，空库也可就绪，探针不会扫描发券记录。
        jdbc.queryForList("SELECT p.inventory_qty, i.max_claims_per_user, b.input_file "
                + "FROM offer_plan p, offer_inventory i, grant_batch b LIMIT 0");
        return Results.success(Map.of("status", "UP"));
    }
}
