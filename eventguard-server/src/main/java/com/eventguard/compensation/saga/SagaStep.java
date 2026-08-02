package com.eventguard.compensation.saga;

import java.util.Map;

/** Saga 单步：一个补偿动作 + 其参数。 */
public record SagaStep(String actionType, Map<String, Object> params) {
}
