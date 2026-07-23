package com.eventguard.event.snapshot;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public class Snapshot {
    private final UUID aggregateId;
    private final String aggregateType;
    private final int version;
    private final Map<String, Object> state;
    private final Instant createdAt;

    public Snapshot(UUID aggregateId, String aggregateType, int version,
                    Map<String, Object> state, Instant createdAt) {
        this.aggregateId = aggregateId;
        this.aggregateType = aggregateType;
        this.version = version;
        this.state = state;
        this.createdAt = createdAt;
    }

    public UUID getAggregateId() { return aggregateId; }
    public String getAggregateType() { return aggregateType; }
    public int getVersion() { return version; }
    public Map<String, Object> getState() { return state; }
    public Instant getCreatedAt() { return createdAt; }
}
