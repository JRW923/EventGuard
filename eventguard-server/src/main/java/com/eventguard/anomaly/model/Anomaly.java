package com.eventguard.anomaly.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** 规则引擎检出的异常 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Anomaly {

    private final String anomalyId;
    private final String ruleId;
    private final UUID aggregateId;
    private final String eventType;
    private final AnomalyLevel level;
    private final Instant detectedAt;
    private final String description;
    private final Map<String, Object> details;

    public Anomaly(String ruleId, UUID aggregateId, String eventType,
                   AnomalyLevel level, String description, Map<String, Object> details) {
        this.anomalyId = UUID.randomUUID().toString();
        this.ruleId = ruleId;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.level = level;
        this.detectedAt = Instant.now();
        this.description = description;
        this.details = details;
    }

    public String getAnomalyId() { return anomalyId; }
    public String getRuleId() { return ruleId; }
    public UUID getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public AnomalyLevel getLevel() { return level; }
    public Instant getDetectedAt() { return detectedAt; }
    public String getDescription() { return description; }
    public Map<String, Object> getDetails() { return details; }
}
