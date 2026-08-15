package com.eventguard.command.handler;

import com.eventguard.common.dto.CommandResult;
import com.eventguard.command.command.Command;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

    public record Entry(UUID commandId, UUID aggregateId, String commandType,
                        String requestHash, CommandResult result) {}

    /** Serialize same-command arrivals so the first committed result is reusable by all retries. */
    public void lock(UUID commandId) {
        long lockKey = commandId.getMostSignificantBits() ^ commandId.getLeastSignificantBits();
        jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> null, lockKey);
    }

    public Optional<Entry> find(UUID commandId) {
        List<Entry> rows = jdbc.query(
                "SELECT command_id, aggregate_id, command_type, request_hash, result::text " +
                        "FROM command_log WHERE command_id = ?",
                (rs, rowNum) -> {
                    String resultJson = rs.getString("result");
                    CommandResult result = null;
                    if (resultJson != null) {
                        try {
                            result = objectMapper.readValue(resultJson, CommandResult.class);
                        } catch (Exception e) {
                            throw new IllegalStateException("反序列化 CommandResult 失败", e);
                        }
                    }
                    return new Entry(
                            rs.getObject("command_id", UUID.class),
                            rs.getObject("aggregate_id", UUID.class),
                            rs.getString("command_type"),
                            rs.getString("request_hash"),
                            result);
                }, commandId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<CommandResult> loadFor(Command command) {
        Optional<Entry> entry = find(command.getCommandId());
        if (entry.isEmpty()) return Optional.empty();
        assertCompatible(entry.get(), command, fingerprint(command));
        return Optional.ofNullable(entry.get().result());
    }

    public String fingerprint(Command command) {
        try {
            // Records have deterministic component order; commandId is included deliberately so
            // the digest is tied to the exact id being protected by the advisory lock.
            byte[] bytes = objectMapper.writeValueAsString(command).getBytes(StandardCharsets.UTF_8);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("计算命令摘要失败", e);
        }
    }

    public void assertCompatible(Entry entry, Command command, String requestHash) {
        boolean sameIdentity = entry.aggregateId().equals(command.getAggregateId())
                && entry.commandType().equals(command.getClass().getSimpleName());
        boolean sameRequest = entry.requestHash() == null || entry.requestHash().equals(requestHash);
        if (!sameIdentity || !sameRequest) {
            throw new IllegalArgumentException("commandId 已用于不同的订单、命令类型或参数");
        }
        if (entry.result() == null) {
            throw new IllegalStateException("commandId 正在处理中，不能返回空结果");
        }
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
        save(commandId, aggregateId, commandType, result, null);
    }

    public void save(UUID commandId, UUID aggregateId, String commandType,
                     CommandResult result, String requestHash) {
        try {
            String resultJson = objectMapper.writeValueAsString(result);
            jdbc.update(
                    "INSERT INTO command_log (command_id, aggregate_id, command_type, request_hash, result, executed_at) " +
                            "VALUES (?, ?, ?, ?, ?::jsonb, now())",
                    commandId, aggregateId, commandType, requestHash, resultJson);
        } catch (Exception e) {
            throw new IllegalStateException("保存 CommandLog 失败", e);
        }
    }
}
