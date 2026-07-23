package com.eventguard.event.store;

import com.eventguard.event.model.DomainEvent;

import java.util.List;
import java.util.UUID;

public interface EventStore {
    void append(UUID aggregateId, List<DomainEvent> events, int expectedVersion);
    List<DomainEvent> load(UUID aggregateId);
    List<DomainEvent> loadFrom(UUID aggregateId, int fromVersion);
}
