package com.eventguard.compensation.action;

import java.util.Map;
import java.util.UUID;

import com.eventguard.compensation.model.CompensationResult;

/**
 * 补偿动作接口（白名单动作实现此接口）。
 */
public interface CompensationAction {

    /** 动作类型，如 REFUND / NOTIFY_DELAY 等 */
    String actionType();

    /** 默认风险等级 */
    String defaultRiskLevel();

    /**
     * 是否需人工审批（对齐设计文档 7.4.2）：默认自动执行，高风险动作覆写此方法。
     *
     * @param aggregateId 聚合根 ID
     * @param params      动作参数
     */
    default boolean requiresApproval(UUID aggregateId, Map<String, Object> params) {
        return false;
    }

    /**
     * 执行补偿动作（已接入真实网关副作用，见各动作实现；MVP 不接 Saga 自动编排前的描述化）。
     *
     * @param aggregateId 聚合根 ID
     * @param params      动作参数
     * @return 动作执行结果描述
     */
    String execute(UUID aggregateId, Map<String, Object> params);

    default CompensationResult executeResult(UUID aggregateId, Map<String, Object> params) {
        return CompensationResult.success(execute(aggregateId, params));
    }
}
