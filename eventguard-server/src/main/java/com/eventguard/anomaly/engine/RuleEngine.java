package com.eventguard.anomaly.engine;

import com.eventguard.anomaly.model.Anomaly;
import com.eventguard.anomaly.model.AnomalyLevel;
import com.eventguard.anomaly.rule.EventRule;
import com.eventguard.event.model.DomainEvent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 规则引擎：遍历规则列表，返回首个命中的异常。
 * 命中即返回（findFirst），未命中返回 empty。
 */
@Component
public class RuleEngine {

    private final List<EventRule> rules;
    private final RuleContextLoader contextLoader;

    public RuleEngine(List<EventRule> rules, RuleContextLoader contextLoader) {
        this.rules = rules;
        this.contextLoader = contextLoader;
    }

    public Optional<Anomaly> evaluate(DomainEvent event) {
        RuleContext ctx = contextLoader.load(event);
        return rules.stream()
                .filter(rule -> rule.matches(event, ctx))
                .findFirst()
                .map(rule -> new Anomaly(
                        rule.ruleId(),
                        event.getAggregateId(),
                        event.getEventType(),
                        rule.level(),
                        buildDescription(rule, event),
                        java.util.Map.of()
                ));
    }

    private String buildDescription(EventRule rule, DomainEvent event) {
        return String.format("规则 %s 命中：事件 %s (aggregate=%s)",
                rule.ruleId(), event.getEventType(), event.getAggregateId());
    }
}
