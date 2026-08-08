package com.eventguard.compensation.model;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI 建议的补偿 Saga 请求体（Item 6b）：aggregateId + 步骤列表。
 * 步骤动作必须在白名单内（CompensationActionRegistry.isSupported），高风险步骤由 Saga 自动落审批单。
 */
public class SagaRequest {

    private UUID aggregateId;
    private List<Step> steps;

    public static class Step {
        private String actionType;
        private Map<String, Object> params;

        public String getActionType() { return actionType; }
        public void setActionType(String actionType) { this.actionType = actionType; }
        public Map<String, Object> getParams() { return params; }
        public void setParams(Map<String, Object> params) { this.params = params; }
    }

    public UUID getAggregateId() { return aggregateId; }
    public void setAggregateId(UUID aggregateId) { this.aggregateId = aggregateId; }
    public List<Step> getSteps() { return steps; }
    public void setSteps(List<Step> steps) { this.steps = steps; }
}
