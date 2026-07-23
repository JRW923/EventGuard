package com.eventguard.common.idempotent;

import java.util.UUID;

public interface IdempotentConsumer {
    boolean isProcessed(String consumerGroup, UUID eventId);
    void markProcessed(String consumerGroup, UUID eventId);
}
