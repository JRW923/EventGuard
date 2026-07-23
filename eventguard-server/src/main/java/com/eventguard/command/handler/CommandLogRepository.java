package com.eventguard.command.handler;

import com.eventguard.common.dto.CommandResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class CommandLogRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public CommandLogRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Optional<CommandResult> loadResult(UUID commandId) {
        List<String> results = jdbc.queryForList(
                "SELECT result::text FROM command_log WHERE command_id = ?",
                String.class, commandId);
        if (results.isEmpty() || results.get(0) == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(results.get(0), CommandResult.class));
        } catch (Exception e) {
            throw new IllegalStateException("反序列化 CommandResult 失败", e);
        }
    }

    public boolean exists(UUID commandId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM command_log WHERE command_id = ?)",
                Boolean.class, commandId);
        return Boolean.TRUE.equals(exists);
    }

    public void save(UUID commandId, UUID aggregateId, String commandType, CommandResult result) {
        try {
            String resultJson = objectMapper.writeValueAsString(result);
            jdbc.update(
                    "INSERT INTO command_log (command_id, aggregate_id, command_type, result, executed_at) " +
                            "VALUES (?, ?, ?, ?::jsonb, now())",
                    commandId, aggregateId, commandType, resultJson);
        } catch (Exception e) {
            throw new IllegalStateException("保存 CommandLog 失败", e);
        }
    }
}
