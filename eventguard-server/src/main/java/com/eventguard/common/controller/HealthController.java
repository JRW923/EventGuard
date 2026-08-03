package com.eventguard.common.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 应用健康/版本端点（P2-18）：前端页脚展示当前版本与后端连通性。
 * 公开端点（AuthFilter / RateLimitFilter 均已放行 /health），不暴露敏感信息。
 */
@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final JdbcTemplate jdbc;
    private final String version;
    private final String kafkaBootstrap;

    public HealthController(JdbcTemplate jdbc,
                            @Value("${eg.app.version:0.1.0-SNAPSHOT}") String version,
                            @Value("${KAFKA_BOOTSTRAP:kafka:9092}") String kafkaBootstrap) {
        this.jdbc = jdbc;
        this.version = version;
        this.kafkaBootstrap = kafkaBootstrap;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("version", version);

        Map<String, String> deps = new LinkedHashMap<>();
        deps.put("db", pingDb() ? "UP" : "DOWN");
        deps.put("kafka", kafkaBootstrap);
        body.put("dependencies", deps);
        return body;
    }

    private boolean pingDb() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (Exception e) {
            log.warn("健康检查：数据库探活失败: {}", e.getMessage());
            return false;
        }
    }
}
