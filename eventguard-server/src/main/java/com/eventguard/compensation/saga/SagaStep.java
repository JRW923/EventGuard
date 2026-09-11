package com.eventguard.compensation.saga;

import java.util.List;
import java.util.Map;

/**
 * Saga 单步：一个补偿动作 + 其参数 + 依赖关系。
 * <p>
 * 依赖用 {@code dependsOn} 显式声明（依赖其它步骤的 id），编排器据此调度，
 * 不再靠数组顺序隐式表达——顺序不再承载语义，重排数组不会改变执行结果。
 * <p>
 * 同一步骤类型可以在一个 Saga 里出现多次（如先冻结再解冻），所以身份用 {@code id} 而非 actionType。
 */
public record SagaStep(String id, String actionType, Map<String, Object> params, List<String> dependsOn) {

    /** 无依赖步骤：id 取 actionType（同一 Saga 内不重复时的便捷写法）。 */
    public SagaStep(String actionType, Map<String, Object> params) {
        this(actionType, actionType, params, List.of());
    }

    public SagaStep(String id, String actionType, Map<String, Object> params, String... dependsOn) {
        this(id, actionType, params, List.of(dependsOn));
    }

    public SagaStep {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("SagaStep.id 必填");
        params = params == null ? Map.of() : Map.copyOf(params);
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }
}
