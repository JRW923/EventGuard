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
        String type = event.getEventType();
        // 预留失败事件：预留即未成功，库存越界天然成立，直接命中
        if ("InventoryReservationFailedEvent".equals(type)) return true;
        if (!"InventoryReservedEvent".equals(type)) return false;
        if (!(event instanceof SimpleEvent se)) return false;

        // ponytail: 真实事件 payload 字段为 quantity（修复此前读 reservedQty 恒为 0 导致 R005 从不触发）
        int reservedQty = se.getInt("quantity");
        return reservedQty > ctx.getActualStock();
    }
}
