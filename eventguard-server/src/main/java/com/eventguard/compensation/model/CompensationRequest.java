package com.eventguard.compensation.model;

import java.util.Map;
import java.util.UUID;

/**
 * 补偿执行请求（POST /compensations）。
 */
public class CompensationRequest {

    private String actionType;       // REFUND / NOTIFY_DELAY / MARK_OUT_OF_STOCK / FREEZE_ORDER / BACKOFF_AND_STOP
    private UUID aggregateId;
    private Map<String, Object> params;

    public CompensationRequest() {}

    public CompensationRequest(String actionType, UUID aggregateId, Map<String, Object> params) {
        this.actionType = actionType;
        this.aggregateId = aggregateId;
        this.params = params == null ? Map.of() : params;
    }

    public String getActionType() { return actionType; }
    public void setActionType(String actionType) { this.actionType = actionType; }

    public UUID getAggregateId() { return aggregateId; }
    public void setAggregateId(UUID aggregateId) { this.aggregateId = aggregateId; }

    public Map<String, Object> getParams() { return params; }
    public void setParams(Map<String, Object> params) { this.params = params; }
}
