package com.eventguard.compensation.action;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/** 退避停止补偿动作（用于死循环重试场景）。 */
@Component
public class BackoffAndStopAction implements CompensationAction {

    @Override
    public String actionType() { return "BACKOFF_AND_STOP"; }

    @Override
    public String defaultRiskLevel() { return "LOW"; }

    @Override
    public String execute(UUID aggregateId, Map<String, Object> params) {
        return "已停止订单 " + aggregateId + " 的重试";
    }
}
