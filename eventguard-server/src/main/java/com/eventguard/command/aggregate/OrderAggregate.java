package com.eventguard.command.aggregate;

import com.eventguard.command.command.*;
import com.eventguard.event.model.*;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 订单聚合根：封装订单状态机与业务规则。
 * 状态机（设计文档 7.1.3）：
 *   null → PENDING_PAYMENT → PAID → CONFIRMED → SHIPPED → DELIVERED → CLOSED
 *   异常分支：PENDING_PAYMENT → PAYMENT_FAILED → (重试) → PENDING_PAYMENT
 *            PAYMENT_FAILED → (重试超 3 次) → CANCELLED
 *            PAID/CONFIRMED → REFUNDED
 *            任意非终态 → CANCELLED
 */
public class OrderAggregate extends AggregateRoot {

    private OrderStatus status;
    private BigDecimal totalAmount;
    private int retryCount;

    public OrderStatus getStatus() { return status; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public int getRetryCount() { return retryCount; }

    // —— 命令处理 ——

    public void handle(CreateOrderCommand cmd) {
        if (status != null) throw new IllegalStateException("订单已存在");
        setAggregateId(cmd.getAggregateId());
        // metadata 携带 userId：R001/R004 规则上下文（RuleContextLoader）按 metadata->>'userId' 聚合用户历史，
        // 此前 metadata 为 null 导致金额偏离/高频规则永不触发（评测模块勘察发现的既存问题）。
        raise(new OrderCreatedEvent(getAggregateId(), getVersion() + 1,
                cmd.userId(), cmd.totalAmount(), Map.of("userId", cmd.userId())));
    }

    public void handle(PayOrderCommand cmd) {
        if (status != OrderStatus.PENDING_PAYMENT) {
            throw new IllegalStateException("只有待支付的订单才能发起支付，当前状态: " + status);
        }
        // B 步：支付改为异步意图+回调。此处仅记录「已发起支付」意图，状态不变；
        // 真实结果由网关回调经 CompletePaymentCommand 落 PaymentCompletedEvent。
        raise(new PaymentRequestedEvent(getAggregateId(), getVersion() + 1, cmd.getCommandId(), null));
    }

    public void handle(CompletePaymentCommand cmd) {
        if (status != OrderStatus.PENDING_PAYMENT) {
            throw new IllegalStateException("只有待支付的订单才能确认支付完成，当前状态: " + status);
        }
        raise(new PaymentCompletedEvent(getAggregateId(), getVersion() + 1, cmd.paymentId(), null));
    }

    public void handle(FailPaymentCommand cmd) {
        if (status != OrderStatus.PENDING_PAYMENT) {
            throw new IllegalStateException("只有待支付的订单才能记录支付失败，当前状态: " + status);
        }
        raise(new PaymentFailedEvent(getAggregateId(), getVersion() + 1, cmd.reason(), null));
    }

    public void handle(RetryPaymentCommand cmd) {
        if (status != OrderStatus.PAYMENT_FAILED) {
            throw new IllegalStateException("只有支付失败的订单才能重试，当前状态: " + status);
        }
        retryCount++;
        if (retryCount > 3) {
            raise(new OrderCancelledEvent(getAggregateId(), getVersion() + 1,
                    "支付重试超限（" + retryCount + " 次）", null));
        } else {
            raise(new PaymentRetriedEvent(getAggregateId(), getVersion() + 1, retryCount, null));
        }
    }

    public void handle(ReserveInventoryCommand cmd) {
        if (status != OrderStatus.PAID) {
            throw new IllegalStateException("只有已支付的订单才能预留库存，当前状态: " + status);
        }
        raise(new InventoryReservedEvent(getAggregateId(), getVersion() + 1,
                cmd.skuId(), cmd.quantity(), null));
    }

    /** 库存预留失败分支：网关返回库存不足，记录失败事件（状态不变，仍 PAID），供 R005/Saga 处理。 */
    public void handleInventoryReservationFailed(ReserveInventoryCommand cmd, String reason) {
        if (status != OrderStatus.PAID) {
            throw new IllegalStateException("只有已支付的订单才能预留库存，当前状态: " + status);
        }
        raise(new InventoryReservationFailedEvent(getAggregateId(), getVersion() + 1,
                cmd.skuId(), cmd.quantity(), reason, null));
    }

    public void handle(ConfirmOrderCommand cmd) {
        if (status != OrderStatus.PAID) {
            throw new IllegalStateException("只有已支付的订单才能确认，当前状态: " + status);
        }
        raise(new OrderConfirmedEvent(getAggregateId(), getVersion() + 1, null));
    }

    public void handle(ShipOrderCommand cmd) {
        if (status != OrderStatus.CONFIRMED) {
            throw new IllegalStateException("只有已确认的订单才能发货，当前状态: " + status);
        }
        raise(new ShippedEvent(getAggregateId(), getVersion() + 1, cmd.trackingNo(), null));
    }

    public void handle(DeliverOrderCommand cmd) {
        if (status != OrderStatus.SHIPPED) {
            throw new IllegalStateException("只有已发货的订单才能送达，当前状态: " + status);
        }
        raise(new DeliveredEvent(getAggregateId(), getVersion() + 1, null));
    }

    public void handle(CloseOrderCommand cmd) {
        if (status != OrderStatus.DELIVERED) {
            throw new IllegalStateException("只有已送达的订单才能关闭，当前状态: " + status);
        }
        raise(new OrderClosedEvent(getAggregateId(), getVersion() + 1, null));
    }

    public void handle(CancelOrderCommand cmd) {
        if (status == OrderStatus.CLOSED || status == OrderStatus.CANCELLED) {
            throw new IllegalStateException("终态订单不能取消，当前状态: " + status);
        }
        raise(new OrderCancelledEvent(getAggregateId(), getVersion() + 1, cmd.reason(), null));
    }

    public void handle(RefundOrderCommand cmd) {
        if (status != OrderStatus.PAID && status != OrderStatus.CONFIRMED) {
            throw new IllegalStateException("只有已支付或已确认的订单才能退款，当前状态: " + status);
        }
        raise(new OrderRefundedEvent(getAggregateId(), getVersion() + 1, cmd.refundAmount(), null));
    }

    // —— 事件应用（用于 raise 与回放） ——

    @Override
    protected void apply(DomainEvent event) {
        if (event instanceof OrderCreatedEvent e) {
            setAggregateId(e.getAggregateId());
            status = OrderStatus.PENDING_PAYMENT;
            totalAmount = e.getTotalAmount();
        } else if (event instanceof PaymentCompletedEvent) {
            status = OrderStatus.PAID;
        } else if (event instanceof PaymentFailedEvent) {
            status = OrderStatus.PAYMENT_FAILED;
        } else if (event instanceof PaymentRetriedEvent e) {
            retryCount = e.getRetryCount();
            status = OrderStatus.PENDING_PAYMENT;
        } else if (event instanceof PaymentRequestedEvent) {
            // 支付意图事件不改状态（仍 PENDING_PAYMENT，待网关回调）
        } else if (event instanceof InventoryReservedEvent) {
            // 不改状态，仅记录
        } else if (event instanceof InventoryReservationFailedEvent) {
            // 库存预留失败不改状态（仍 PAID，触发 R005/Saga）
        } else if (event instanceof OrderConfirmedEvent) {
            status = OrderStatus.CONFIRMED;
        } else if (event instanceof ShippedEvent) {
            status = OrderStatus.SHIPPED;
        } else if (event instanceof DeliveredEvent) {
            status = OrderStatus.DELIVERED;
        } else if (event instanceof OrderClosedEvent) {
            status = OrderStatus.CLOSED;
        } else if (event instanceof OrderCancelledEvent) {
            status = OrderStatus.CANCELLED;
        } else if (event instanceof OrderRefundedEvent) {
            status = OrderStatus.REFUNDED;
        } else if (event instanceof CompensationExecutedEvent) {
            // 补偿事件不改订单状态，仅留痕（修复：此前回放会抛 IllegalStateException）
        } else if (event instanceof OrderRefundRequestedEvent) {
            // 退款意图事件不改状态（订单仍 PAID，待退款结果确认）
        } else {
            throw new IllegalStateException("未知事件类型: " + event.getEventType());
        }
    }

    // —— 快照序列化 ——

    public Map<String, Object> toStateMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("aggregateId", getAggregateId());
        m.put("status", status != null ? status.name() : null);
        m.put("totalAmount", totalAmount != null ? totalAmount.toString() : null);
        m.put("version", getVersion());
        m.put("retryCount", retryCount);
        return m;
    }

    public static OrderAggregate fromStateMap(Map<String, Object> state) {
        OrderAggregate agg = new OrderAggregate();
        Object idObj = state.get("aggregateId");
        if (idObj instanceof UUID u) agg.setAggregateId(u);
        else if (idObj instanceof String s) agg.setAggregateId(UUID.fromString(s));
        String statusName = (String) state.get("status");
        if (statusName != null) agg.status = OrderStatus.valueOf(statusName);
        String amt = (String) state.get("totalAmount");
        if (amt != null) agg.totalAmount = new BigDecimal(amt);
        Number ver = (Number) state.get("version");
        if (ver != null) agg.setVersion(ver.intValue());
        Number rc = (Number) state.get("retryCount");
        if (rc != null) agg.retryCount = rc.intValue();
        return agg;
    }
}
