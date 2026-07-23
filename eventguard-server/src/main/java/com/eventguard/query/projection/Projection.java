package com.eventguard.query.projection;

import com.eventguard.event.model.DomainEvent;

public interface Projection {
    void handle(DomainEvent event);
    void reset();
}
