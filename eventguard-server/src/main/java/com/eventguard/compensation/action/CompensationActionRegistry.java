package com.eventguard.compensation.action;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 补偿动作注册表：维护 actionType → CompensationAction 映射，提供白名单校验。
 *
 * ponytail: 白名单为硬编码枚举（5 个动作），由 Spring 收集所有 CompensationAction Bean 自动注册；
 * 新增动作类型需实现 CompensationAction 并被 Spring 扫描，无需改这里。
 */
@Component
public class CompensationActionRegistry {

    private static final Logger log = LoggerFactory.getLogger(CompensationActionRegistry.class);
    private final Map<String, CompensationAction> actions = new HashMap<>();

    public CompensationActionRegistry(List<CompensationAction> actionBeans) {
        for (CompensationAction action : actionBeans) {
            actions.put(action.actionType(), action);
            log.info("注册补偿动作：{}（风险：{}）", action.actionType(), action.defaultRiskLevel());
        }
    }

    /** 判断 actionType 是否在白名单 */
    public boolean isSupported(String actionType) {
        return actions.containsKey(actionType);
    }

    /** 获取动作实现 */
    public CompensationAction get(String actionType) {
        return actions.get(actionType);
    }
}
