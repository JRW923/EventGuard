package com.eventguard.command.aggregate;

import com.eventguard.event.model.DomainEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 聚合根基类：管理 pendingEvents 与 version。
 * - version 表示「已持久化版本」，新事件版本 = version + 1
 * - raise(event) 将事件加入 pendingEvents 并调用 apply 更新状态
 * - applyEvent(event) 仅更新状态（用于从事件流重建聚合根，不加入 pending）
 * - flushPendingEvents() 返回待持久化事件并清空列表，同时更新 version
 */
public abstract class AggregateRoot {

    private UUID aggregateId;
    private int version = 0;
    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    protected void raise(DomainEvent event) {
        pendingEvents.add(event);
        apply(event);
    }

    public void applyEvent(DomainEvent event) {
        apply(event);
        this.version = event.getVersion();
    }

    protected abstract void apply(DomainEvent event);

    public List<DomainEvent> flushPendingEvents() {
        List<DomainEvent> events = new ArrayList<>(pendingEvents);
        pendingEvents.clear();
        if (!events.isEmpty()) {
            this.version = events.get(events.size() - 1).getVersion();
        }
        return events;
    }

    public int getVersion() { return version; }
    public UUID getAggregateId() { return aggregateId; }
    protected void setAggregateId(UUID aggregateId) { this.aggregateId = aggregateId; }
    protected void setVersion(int version) { this.version = version; }
}
