package com.eventguard.command.aggregate;

import com.eventguard.command.command.*;
import com.eventguard.event.model.DomainEvent;
import com.eventguard.event.model.OrderCancelledEvent;
import com.eventguard.event.model.OrderCreatedEvent;
import com.eventguard.event.model.PaymentRequestedEvent;
import com.eventguard.event.model.PaymentRetriedEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderAggregateTest {

    private OrderAggregate newOrder() {
        OrderAggregate agg = new OrderAggregate();
        agg.handle(new CreateOrderCommand(UUID.randomUUID(), UUID.randomUUID(), "user-1", new BigDecimal("99.00")));
        agg.flushPendingEvents();
        return agg;
    }

    @Test
    void createOrder_should_set_status_to_pending_payment() {
        OrderAggregate agg = new OrderAggregate();
        agg.handle(new CreateOrderCommand(UUID.randomUUID(), UUID.randomUUID(), "user-1", new BigDecimal("99.00")));

        assertThat(agg.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(agg.getTotalAmount()).isEqualByComparingTo("99.00");
        List<DomainEvent> events = agg.flushPendingEvents();
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isInstanceOf(OrderCreatedEvent.class);
    }

    @Test
    void createOrder_on_existing_order_should_throw() {
        OrderAggregate agg = newOrder();
        assertThatThrownBy(() -> agg.handle(
                new CreateOrderCommand(UUID.randomUUID(), agg.getAggregateId(), "user-2", new BigDecimal("1.00"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("订单已存在");
    }

    @Test
    void payOrder_should_emit_payment_requested_and_stay_pending() {
        OrderAggregate agg = newOrder();
        agg.handle(new PayOrderCommand(UUID.randomUUID(), agg.getAggregateId(), "pay-1"));
        // B 步：支付改为异步意图，pay 只记录 PaymentRequestedEvent，状态不变
        assertThat(agg.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(agg.flushPendingEvents().get(0)).isInstanceOf(PaymentRequestedEvent.class);
    }

    @Test
    void completePayment_should_transition_to_paid() {
        OrderAggregate agg = newOrder();
        agg.handle(new PayOrderCommand(UUID.randomUUID(), agg.getAggregateId(), "pay-1"));
        agg.flushPendingEvents();
        agg.handle(new CompletePaymentCommand(UUID.randomUUID(), agg.getAggregateId(), "gw-pay-1"));
        assertThat(agg.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void payOrder_from_wrong_state_should_throw() {
        OrderAggregate agg = newOrder();
        agg.handle(new PayOrderCommand(UUID.randomUUID(), agg.getAggregateId(), "pay-1"));
        agg.flushPendingEvents();
        // 已完成支付（PAID）后再次发起支付应抛错
        agg.handle(new CompletePaymentCommand(UUID.randomUUID(), agg.getAggregateId(), "gw-pay-1"));
        assertThatThrownBy(() -> agg.handle(
                new PayOrderCommand(UUID.randomUUID(), agg.getAggregateId(), "pay-2")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void illegal_jump_pending_payment_to_shipped_should_throw() {
        OrderAggregate agg = newOrder();
        assertThatThrownBy(() -> agg.handle(
                new ShipOrderCommand(UUID.randomUUID(), agg.getAggregateId(), "trk-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("只有已确认的订单才能发货");
    }

    @Test
    void full_happy_path_create_pay_reserve_confirm_ship_deliver_close() {
        OrderAggregate agg = newOrder();
        UUID orderId = agg.getAggregateId();

        agg.handle(new PayOrderCommand(UUID.randomUUID(), orderId, "pay-1"));
        agg.handle(new CompletePaymentCommand(UUID.randomUUID(), orderId, "gw-pay-1"));
        agg.handle(new ReserveInventoryCommand(UUID.randomUUID(), orderId, "sku-1", 1));
        agg.handle(new ConfirmOrderCommand(UUID.randomUUID(), orderId));
        agg.handle(new ShipOrderCommand(UUID.randomUUID(), orderId, "trk-1"));
        agg.handle(new DeliverOrderCommand(UUID.randomUUID(), orderId));
        agg.handle(new CloseOrderCommand(UUID.randomUUID(), orderId));

        assertThat(agg.getStatus()).isEqualTo(OrderStatus.CLOSED);
        // requested + completed + reserve + confirm + ship + deliver + close = 7（create 已在 newOrder 中 flush）
        assertThat(agg.flushPendingEvents()).hasSize(7);
    }

    @Test
    void payment_retry_should_return_to_pending_and_increment_count() {
        OrderAggregate agg = newOrder();
        agg.handle(new FailPaymentCommand(UUID.randomUUID(), agg.getAggregateId(), "余额不足"));
        agg.flushPendingEvents();
        assertThat(agg.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);

        agg.handle(new RetryPaymentCommand(UUID.randomUUID(), agg.getAggregateId()));
        agg.flushPendingEvents();
        assertThat(agg.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(agg.getRetryCount()).isEqualTo(1);
    }

    @Test
    void payment_retry_over_3_times_should_cancel() {
        OrderAggregate agg = newOrder();
        // 3 次重试
        for (int i = 1; i <= 3; i++) {
            agg.handle(new FailPaymentCommand(UUID.randomUUID(), agg.getAggregateId(), "失败"));
            agg.flushPendingEvents();
            agg.handle(new RetryPaymentCommand(UUID.randomUUID(), agg.getAggregateId()));
            agg.flushPendingEvents();
        }
        assertThat(agg.getRetryCount()).isEqualTo(3);
        // 第 4 次失败后重试 → 自动取消
        agg.handle(new FailPaymentCommand(UUID.randomUUID(), agg.getAggregateId(), "失败"));
        agg.flushPendingEvents();
        agg.handle(new RetryPaymentCommand(UUID.randomUUID(), agg.getAggregateId()));

        assertThat(agg.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(agg.flushPendingEvents().get(0)).isInstanceOf(OrderCancelledEvent.class);
    }

    @Test
    void refund_from_paid_should_transition_to_refunded() {
        OrderAggregate agg = newOrder();
        agg.handle(new PayOrderCommand(UUID.randomUUID(), agg.getAggregateId(), "pay-1"));
        agg.handle(new CompletePaymentCommand(UUID.randomUUID(), agg.getAggregateId(), "gw-pay-1"));
        agg.flushPendingEvents();
        agg.handle(new RefundOrderCommand(UUID.randomUUID(), agg.getAggregateId(), new BigDecimal("99.00")));
        assertThat(agg.getStatus()).isEqualTo(OrderStatus.REFUNDED);
    }

    @Test
    void closed_order_cannot_transition() {
        OrderAggregate agg = newOrder();
        UUID id = agg.getAggregateId();
        agg.handle(new PayOrderCommand(UUID.randomUUID(), id, "p"));
        agg.handle(new CompletePaymentCommand(UUID.randomUUID(), id, "gw-p"));
        agg.handle(new ReserveInventoryCommand(UUID.randomUUID(), id, "s", 1));
        agg.handle(new ConfirmOrderCommand(UUID.randomUUID(), id));
        agg.handle(new ShipOrderCommand(UUID.randomUUID(), id, "t"));
        agg.handle(new DeliverOrderCommand(UUID.randomUUID(), id));
        agg.handle(new CloseOrderCommand(UUID.randomUUID(), id));
        agg.flushPendingEvents();

        assertThatThrownBy(() -> agg.handle(new CancelOrderCommand(UUID.randomUUID(), id, "不想要了")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("终态订单");
    }
}
