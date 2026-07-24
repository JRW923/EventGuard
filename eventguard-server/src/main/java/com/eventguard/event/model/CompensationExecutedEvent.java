package com.eventguard.event.model;

import java.util.Map;
import java.util.UUID;

/**
 * 补偿已执行事件（记录补偿动作的执行）。
 */
public class CompensationExecutedEvent extends DomainEvent {

    private final String actionType;
    private final Map<String, Object> params;

    public CompensationExecutedEvent(UUID aggregateId, int version, String actionType,
                                     Map<String, Object> params, Map<String, String> metadata) {
        super(aggregateId, version, metadata);
        this.actionType = actionType;
        this.params = params;
    }

    @Override
    public Object getPayload() {
        return Map.of(
                "orderId", getAggregateId(),
                "actionType", actionType,
                "params", params
        );
    }

    public String getActionType() { return actionType; }
    public Map<String, Object> getParams() { return params; }
}
