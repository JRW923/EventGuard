package com.eventguard.event.snapshot;

import java.util.Optional;
import java.util.UUID;

public interface SnapshotStore {
    Optional<Snapshot> load(UUID aggregateId);
    void save(Snapshot snapshot);
}
