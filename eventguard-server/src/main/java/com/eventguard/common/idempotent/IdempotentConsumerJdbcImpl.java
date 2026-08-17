package com.eventguard.common.idempotent;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 幂等消费者实现：基于 idempotent_consumers 表（PK: consumer_group + event_id）。
 * - isProcessed：SELECT 是否存在
 * - markProcessed：INSERT，若已存在则忽略（DuplicateKeyException）
 */
@Component
public class IdempotentConsumerJdbcImpl implements IdempotentConsumer {

    private final JdbcTemplate jdbc;

    public IdempotentConsumerJdbcImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isProcessed(String consumerGroup, UUID eventId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM idempotent_consumers WHERE consumer_group = ? AND event_id = ?)",
                Boolean.class, consumerGroup, eventId);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public void markProcessed(String consumerGroup, UUID eventId) {
        tryMarkProcessed(consumerGroup, eventId);
    }

    @Override
    public boolean tryMarkProcessed(String consumerGroup, UUID eventId) {
        try {
            return jdbc.update(
                    "INSERT INTO idempotent_consumers (consumer_group, event_id, processed_at) VALUES (?, ?, now()) " +
                            "ON CONFLICT (consumer_group, event_id) DO NOTHING",
                    consumerGroup, eventId) == 1;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
