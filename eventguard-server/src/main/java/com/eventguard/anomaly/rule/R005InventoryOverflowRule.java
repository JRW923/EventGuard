package com.eventguard.anomaly.rule;

import com.eventguard.anomaly.engine.RuleContext;
import com.eventguard.anomaly.model.AnomalyLevel;
import com.eventguard.anomaly.model.SimpleEvent;
import com.eventguard.event.model.DomainEvent;
import org.springframework.stereotype.Component;

/** R005：库存越界规则 — reservedQty > actualStock */
@Component
public class R005InventoryOverflowRule implements EventRule {

    @Override
    public String ruleId() { return "R005"; }

    @Override
    public AnomalyLevel level() { return AnomalyLevel.ERROR; }

    @Override
    public boolean matches(DomainEvent event, RuleContext ctx) {
        if (!"InventoryReservedEvent".equals(event.getEventType())) return false;
        if (!(event instanceof SimpleEvent se)) return false;

        int reservedQty = se.getInt("reservedQty");
        return reservedQty > ctx.getActualStock();
    }
}
