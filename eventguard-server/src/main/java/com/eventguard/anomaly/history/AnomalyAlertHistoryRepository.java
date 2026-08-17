package com.eventguard.anomaly.history;

import com.eventguard.anomaly.model.AnomalyAlert;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 告警历史持久化：告警落库供重启后检索与 WS 断线补拉。
 * ponytail: 单表无分区，长期增长的清理与告警检索接口（按规则/时间过滤）为升级路径。
 */
@Repository
public class AnomalyAlertHistoryRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AnomalyAlertHistoryRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** 落库原始告警 JSON；anomaly_id 冲突（重复投递）幂等忽略。写入失败抛出，交由 Kafka 重试/DLT。 */
    public void save(String rawJson, AnomalyAlert alert) {
        // anomaly_id 缺失时生成随机键：宁可存下也不因 PK 冲突丢告警
        String anomalyId = alert.getAnomalyId() != null ? alert.getAnomalyId()
                : "generated-" + UUID.randomUUID();
        jdbc.update(
                "INSERT INTO anomaly_alerts (anomaly_id, rule_id, aggregate_id, level, source, payload) " +
                        "VALUES (?, ?, ?, ?, ?, ?::jsonb) ON CONFLICT (anomaly_id) DO NOTHING",
                anomalyId, alert.getRuleId(), alert.getAggregateId(), alert.getLevel(),
                alert.getSource(), rawJson);
    }

    /** 最近 limit 条告警（最新在前），payload 原样反序列化。 */
    public List<AnomalyAlert> recent(int limit) {
        return jdbc.query(
                "SELECT payload FROM anomaly_alerts ORDER BY received_at DESC, anomaly_id DESC LIMIT ?",
                (rs, i) -> {
                    try {
                        return objectMapper.readValue(rs.getString("payload"), AnomalyAlert.class);
                    } catch (Exception e) {
                        // 单条畸形 payload 不应拖垮整页历史查询
                        throw new IllegalStateException("告警 payload 反序列化失败", e);
                    }
                },
                limit);
    }
}
