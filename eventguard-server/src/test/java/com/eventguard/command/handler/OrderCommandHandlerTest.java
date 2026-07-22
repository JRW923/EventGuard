package com.eventguard.command.handler;

import com.eventguard.command.command.CreateOrderCommand;
import com.eventguard.common.dto.CommandResult;
import com.eventguard.event.model.DomainEvent;
import com.eventguard.event.model.OrderCreatedEvent;
import com.eventguard.event.store.EventStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderCommandHandlerTest {

    @Mock
    EventStore eventStore;

    @InjectMocks
    OrderCommandHandler handler;

    @Test
    void createOrder_should_append_order_created_event_with_version_1() {
        // given
        UUID orderId = UUID.randomUUID();
        CreateOrderCommand cmd = new CreateOrderCommand(
                UUID.randomUUID(), orderId, "user-1", new BigDecimal("99.00"));

        // when
        CommandResult result = handler.handle(cmd);

        // then
        assertThat(result.success()).isTrue();
        assertThat(result.version()).isEqualTo(1);

        // 验证 append 被调用，expectedVersion=0（新订单），事件为 OrderCreatedEvent
        verify(eventStore).append(eq(orderId), argThat(events -> {
            DomainEvent e = events.get(0);
            return e instanceof OrderCreatedEvent
                    && e.getAggregateId().equals(orderId)
                    && e.getVersion() == 1;
        }), eq(0));
    }
}
