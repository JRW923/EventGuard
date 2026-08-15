package com.eventguard.command.aggregate;

import com.eventguard.event.model.DomainEvent;
import com.eventguard.event.model.OrderCreatedEvent;
import com.eventguard.event.snapshot.Snapshot;
import com.eventguard.event.snapshot.SnapshotStore;
import com.eventguard.event.store.EventStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AggregateRepositoryTest {

    @Mock EventStore eventStore;
    @Mock SnapshotStore snapshotStore;
    ObjectMapper om = new ObjectMapper();
    @InjectMocks AggregateRepository repo;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        repo = new AggregateRepository(eventStore, snapshotStore, om);
    }

    @Test
    void load_without_snapshot_should_replay_all_events() {
        UUID orderId = UUID.randomUUID();
        when(snapshotStore.load(orderId)).thenReturn(Optional.empty());
        OrderCreatedEvent e1 = new OrderCreatedEvent(orderId, 1, "u1", new BigDecimal("99"), null);
        when(eventStore.loadFrom(orderId, 0)).thenReturn(List.of(e1));

        OrderAggregate agg = repo.load(orderId);

        assertThat(agg.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(agg.getVersion()).isEqualTo(1);
        assertThat(agg.flushPendingEvents()).isEmpty();
    }

    @Test
    void load_with_snapshot_should_replay_only_incremental_events() {
        UUID orderId = UUID.randomUUID();
        Map<String, Object> state = Map.of(
                "aggregateId", orderId.toString(),
                "status", "PAID",
                "totalAmount", "99.0",
                "version", 2,
                "retryCount", 0);
        when(snapshotStore.load(orderId)).thenReturn(Optional.of(
                new Snapshot(orderId, "Order", 2, state, java.time.Instant.now())));
        when(eventStore.loadFrom(orderId, 2)).thenReturn(List.of());

        OrderAggregate agg = repo.load(orderId);

        assertThat(agg.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(agg.getVersion()).isEqualTo(2);
    }

    @Test
    void save_should_append_events_and_save_snapshot_at_version_100() {
        UUID orderId = UUID.randomUUID();
        OrderAggregate agg = new OrderAggregate();
        agg.handle(new com.eventguard.command.command.CreateOrderCommand(
                UUID.randomUUID(), orderId, "u1", new BigDecimal("99")));
        // 模拟版本到 100：手动 setVersion
        // 这里用反射模拟大量事件不现实，直接验证 save 调用 eventStore.append
        agg.flushPendingEvents();
        // 制造一个 version=100 的场景
        OrderAggregate agg100 = spy(agg);
        when(agg100.getVersion()).thenReturn(100);
        when(agg100.flushPendingEvents()).thenReturn(List.of(
                new OrderCreatedEvent(orderId, 100, "u1", new BigDecimal("99"), null)));
        when(agg100.getAggregateId()).thenReturn(orderId);
        when(agg100.toStateMap()).thenReturn(Map.of());

        repo.save(agg100);

        verify(eventStore).append(eq(orderId), anyList(), eq(99));
        verify(snapshotStore).save(any(Snapshot.class));
    }
}
