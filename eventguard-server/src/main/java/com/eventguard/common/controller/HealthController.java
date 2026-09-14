package com.eventguard.common.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import java.time.Duration;
import java.util.Properties;
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
        body.put("version", version);

        Map<String, String> deps = new LinkedHashMap<>();
        boolean dbUp = pingDb();
        deps.put("db", dbUp ? "UP" : "DOWN");
        boolean kafkaUp = pingKafka();
        deps.put("kafka", kafkaUp ? "UP" : "DOWN");
        body.put("status", dbUp && kafkaUp ? "UP" : "DOWN");
        body.put("dependencies", deps);
        return body;
    }

    // 包可见（非 private）：单测用 spy 覆盖探活结果，避免依赖真实 Kafka
    boolean pingKafka() {
        try {
            Properties props = new Properties();
            props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrap);
            props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "1000");
            try (AdminClient client = AdminClient.create(props)) {
                client.describeCluster().nodes().get(1, java.util.concurrent.TimeUnit.SECONDS);
                return true;
            }
        } catch (Exception e) {
            log.warn("健康检查：Kafka 探活失败: {}", e.getMessage());
            return false;
        }
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
