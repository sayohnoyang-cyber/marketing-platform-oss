package com.sayohno.marketing.web;

import com.sayohno.marketing.common.config.MarketingProperties;
import com.sayohno.marketing.common.outbox.OutboxEventMapper;
import com.sayohno.marketing.common.result.Result;
import com.sayohno.marketing.support.TestIdentityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证真实业务探活端点（含异常返回 503）与 Actuator 公开健康检查探针。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestIdentityConfiguration.class)
class HealthControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthAndActuatorReportUpWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.components.db.status").value("UP"))
                .andExpect(jsonPath("$.data.components.redis.status").value("SKIPPED"))
                .andExpect(jsonPath("$.data.components.outbox.status").value("UP"));

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void returns503WhenDatabaseProbeFails() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("connection refused"));
        OutboxEventMapper outboxMapper = mock(OutboxEventMapper.class);
        when(outboxMapper.countPending()).thenReturn(0L);
        when(outboxMapper.countDead()).thenReturn(0L);
        MarketingProperties properties = new MarketingProperties();
        properties.getRedis().setEnabled(false);

        HealthController controller = new HealthController(dataSource, null, outboxMapper, properties);
        ResponseEntity<Result<Map<String, Object>>> response = controller.health();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        Map<String, Object> data = response.getBody().getData();
        assertEquals("DOWN", data.get("status"));
        Map<String, Object> components = (Map<String, Object>) data.get("components");
        Map<String, Object> db = (Map<String, Object>) components.get("db");
        assertEquals("DOWN", db.get("status"));
    }
}
