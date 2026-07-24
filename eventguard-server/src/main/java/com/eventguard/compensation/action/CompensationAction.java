package com.eventguard.compensation.action;

import java.util.Map;
import java.util.UUID;

/**
 * 补偿动作接口（白名单动作实现此接口）。
 */
public interface CompensationAction {

    /** 动作类型，如 REFUND / NOTIFY_DELAY 等 */
    String actionType();

    /** 默认风险等级 */
    String defaultRiskLevel();

    /**
     * 执行补偿动作（MVP：仅记录，不实际触发业务命令；V2 接 Saga 编排）。
     *
     * @param aggregateId 聚合根 ID
     * @param params      动作参数
     * @return 动作执行结果描述
     */
    String execute(UUID aggregateId, Map<String, Object> params);
}
