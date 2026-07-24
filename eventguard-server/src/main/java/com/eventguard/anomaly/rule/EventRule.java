package com.eventguard.anomaly.rule;

import com.eventguard.anomaly.engine.RuleContext;
import com.eventguard.anomaly.model.AnomalyLevel;
import com.eventguard.event.model.DomainEvent;

/** 事件级规则接口 */
public interface EventRule {

    /** 规则 ID，如 "R001" */
    String ruleId();

    /** 判断事件是否命中规则 */
    boolean matches(DomainEvent event, RuleContext ctx);

    /** 命中后的异常级别 */
    AnomalyLevel level();
}
