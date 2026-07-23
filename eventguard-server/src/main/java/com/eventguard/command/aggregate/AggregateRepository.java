package com.eventguard.command.aggregate;

import com.eventguard.event.model.DomainEvent;
import com.eventguard.event.snapshot.Snapshot;
import com.eventguard.event.snapshot.SnapshotStore;
import com.eventguard.event.store.EventStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 聚合根仓储：负责加载（快照+增量事件回放）与保存（事件 append + 触发快照）。
 * 快照策略：每 100 个事件打一次快照（设计文档 7.1.4）。
 */
@Repository
public class AggregateRepository {

    private static final int SNAPSHOT_INTERVAL = 100;

    private final EventStore eventStore;
    private final SnapshotStore snapshotStore;
    private final ObjectMapper objectMapper;

    public AggregateRepository(EventStore eventStore, SnapshotStore snapshotStore, ObjectMapper objectMapper) {
        this.eventStore = eventStore;
        this.snapshotStore = snapshotStore;
        this.objectMapper = objectMapper;
    }

    public OrderAggregate load(UUID orderId) {
        Optional<Snapshot> snapOpt = snapshotStore.load(orderId);
        OrderAggregate agg;
        int fromVersion;
        if (snapOpt.isPresent()) {
            Snapshot snap = snapOpt.get();
            agg = OrderAggregate.fromStateMap(snap.getState());
            fromVersion = snap.getVersion() + 1;
        } else {
            agg = new OrderAggregate();
            fromVersion = 0;
        }
        List<DomainEvent> events = eventStore.loadFrom(orderId, fromVersion);
        events.forEach(agg::applyEvent);
        return agg;
    }

    public void save(OrderAggregate aggregate) {
        List<DomainEvent> newEvents = aggregate.flushPendingEvents();
        if (newEvents.isEmpty()) return;
        int expectedVersion = aggregate.getVersion() - newEvents.size();
        eventStore.append(aggregate.getAggregateId(), newEvents, expectedVersion);
        // 每 SNAPSHOT_INTERVAL 个事件打一次快照
        if (aggregate.getVersion() > 0 && aggregate.getVersion() % SNAPSHOT_INTERVAL == 0) {
            snapshotStore.save(new Snapshot(
                    aggregate.getAggregateId(),
                    "Order",
                    aggregate.getVersion(),
                    aggregate.toStateMap(),
                    Instant.now()
            ));
        }
    }
}
