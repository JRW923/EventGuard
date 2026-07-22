package com.eventguard.command.handler;

import com.eventguard.command.command.CreateOrderCommand;
import com.eventguard.common.dto.CommandResult;
import com.eventguard.event.model.OrderCreatedEvent;
import com.eventguard.event.store.EventStore;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OrderCommandHandler {

    private final EventStore eventStore;

    public OrderCommandHandler(EventStore eventStore) {
        this.eventStore = eventStore;
    }

    public CommandResult handle(CreateOrderCommand cmd) {
        // M1 最小版：新订单 expectedVersion=0，产生 version=1 的事件
        OrderCreatedEvent event = new OrderCreatedEvent(
                cmd.orderId(), 1, cmd.userId(), cmd.totalAmount(), null);
        eventStore.append(cmd.orderId(), List.of(event), 0);
        return CommandResult.success(1);
    }
}
