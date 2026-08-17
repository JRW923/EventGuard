package com.eventguard.common.idempotent;

import java.util.UUID;

public interface IdempotentConsumer {
    boolean isProcessed(String consumerGroup, UUID eventId);
    void markProcessed(String consumerGroup, UUID eventId);

    /** 原子占位：插入成功返回 true（首次），冲突返回 false（已处理）。避免先查后插的竞态窗口。 */
    boolean tryMarkProcessed(String consumerGroup, UUID eventId);
}
