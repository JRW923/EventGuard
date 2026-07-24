package com.eventguard.anomaly.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/** 从 Kafka anomaly-alerts topic 接收的异常告警 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnomalyAlert {

    @JsonProperty("anomaly_id")
    private String anomalyId;

    @JsonProperty("rule_id")
    private String ruleId;

    @JsonProperty("aggregate_id")
    private String aggregateId;  // 用 String 而非 UUID：避免非 UUID 字符串导致反序列化阶段静默丢消息

    @JsonProperty("event_type")
    private String eventType;

    @JsonProperty("level")
    private String level;  // INFO / WARN / ERROR

    @JsonProperty("source")
    private String source;  // RULE / IF / PROCESS

    @JsonProperty("priority")
    private String priority;  // HIGH / LOW

    @JsonProperty("detected_at")
    private String detectedAt;

    @JsonProperty("description")
    private String description;

    @JsonProperty("details")
    private Map<String, Object> details;

    // Jackson 需要无参构造器
    public AnomalyAlert() {}

    public AnomalyAlert(String anomalyId, String ruleId, String aggregateId, String eventType,
                        String level, String source, String priority, String detectedAt,
                        String description, Map<String, Object> details) {
        this.anomalyId = anomalyId;
        this.ruleId = ruleId;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.level = level;
        this.source = source;
        this.priority = priority;
        this.detectedAt = detectedAt;
        this.description = description;
        this.details = details;
    }

    public String getAnomalyId() { return anomalyId; }
    public String getRuleId() { return ruleId; }
    public String getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public String getLevel() { return level; }
    public String getSource() { return source; }
    public String getPriority() { return priority; }
    public String getDetectedAt() { return detectedAt; }
    public String getDescription() { return description; }
    public Map<String, Object> getDetails() { return details; }

    public void setAnomalyId(String anomalyId) { this.anomalyId = anomalyId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }
    public void setAggregateId(String aggregateId) { this.aggregateId = aggregateId; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public void setLevel(String level) { this.level = level; }
    public void setSource(String source) { this.source = source; }
    public void setPriority(String priority) { this.priority = priority; }
    public void setDetectedAt(String detectedAt) { this.detectedAt = detectedAt; }
    public void setDescription(String description) { this.description = description; }
    public void setDetails(Map<String, Object> details) { this.details = details; }
}
