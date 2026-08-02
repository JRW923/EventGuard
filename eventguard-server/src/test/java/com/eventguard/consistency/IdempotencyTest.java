package com.eventguard.consistency;

import com.eventguard.command.aggregate.AggregateRepository;
import com.eventguard.command.command.CreateOrderCommand;
import com.eventguard.command.handler.CommandLogRepository;
import com.eventguard.command.handler.CommandRetryTemplate;
import com.eventguard.command.handler.OrderCommandHandler;
import com.eventguard.common.dto.CommandResult;
import com.eventguard.event.snapshot.SnapshotStoreJdbcImpl;
import com.eventguard.event.store.EventDeserializer;
import com.eventguard.event.store.EventStoreJdbcImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 幂等命令测试：
 * 同一 commandId 重复提交，只执行一次，返回首次结果。
 *
 * 默认跳过（本地无 Docker / 用户指示云端验证）；
 * 在云服务器上用 mvn test -Deventguard.run.integration=true 启用。
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "eventguard.run.integration", matches = "true")
class IdempotencyTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("eventguard")
            .withUsername("eventguard")
            .withPassword("eventguard");

    static JdbcTemplate jdbc;
    static OrderCommandHandler handler;

    @BeforeAll
    static void setup() throws Exception {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(pg.getJdbcUrl());
        ds.setUser(pg.getUsername());
        ds.setPassword(pg.getPassword());
        jdbc = new JdbcTemplate(ds);

        jdbc.execute(readResource("/db/migration/V1__init.sql"));
        jdbc.execute(readResource("/db/migration/V2__full_schema.sql"));

        ObjectMapper om = new ObjectMapper();
        EventDeserializer deserializer = new EventDeserializer(om);
        EventStoreJdbcImpl eventStore = new EventStoreJdbcImpl(jdbc, om, deserializer);
        SnapshotStoreJdbcImpl snapshotStore = new SnapshotStoreJdbcImpl(jdbc, om);
        AggregateRepository aggregateRepository = new AggregateRepository(eventStore, snapshotStore, om);
        CommandLogRepository commandLogRepository = new CommandLogRepository(jdbc, om);
        CommandRetryTemplate retryTemplate = new CommandRetryTemplate();
        handler = new OrderCommandHandler(aggregateRepository, commandLogRepository, retryTemplate,
                new DataSourceTransactionManager(ds), new com.eventguard.gateway.mock.MockInventoryGateway(
                        new com.eventguard.gateway.config.GatewayProperties("mock", "mock", "mock", 0.0, 0, "SKU-A:100")));
    }

    private static String readResource(String path) throws Exception {
        try (var is = IdempotencyTest.class.getResourceAsStream(path)) {
            assert is != null : "资源不存在: " + path;
            return new String(is.readAllBytes());
        }
    }

    @Test
    void duplicate_commandId_should_return_first_result_and_not_double_execute() {
        UUID commandId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        CreateOrderCommand cmd = new CreateOrderCommand(commandId, orderId, "user-1", new BigDecimal("99.00"));

        CommandResult first = handler.handle(cmd);
        CommandResult second = handler.handle(cmd);

        assertThat(first.success()).isTrue();
        assertThat(second.success()).isTrue();
        assertThat(second.version()).isEqualTo(first.version());

        // 验证只产生 1 条事件
        Integer eventCount = jdbc.queryForObject(
                "SELECT count(*) FROM domain_events WHERE aggregate_id = ?",
                Integer.class, orderId);
        assertThat(eventCount).isEqualTo(1);

        // 验证只产生 1 条命令日志
        Integer logCount = jdbc.queryForObject(
                "SELECT count(*) FROM command_log WHERE command_id = ?",
                Integer.class, commandId);
        assertThat(logCount).isEqualTo(1);
    }

    @Test
    void different_commandId_should_both_execute() {
        UUID orderId = UUID.randomUUID();

        CommandResult first = handler.handle(new CreateOrderCommand(
                UUID.randomUUID(), orderId, "u1", new BigDecimal("99")));
        // 第二次用同 orderId 但不同 commandId 会失败（订单已存在），改用不同 orderId
        UUID orderId2 = UUID.randomUUID();
        CommandResult second = handler.handle(new CreateOrderCommand(
                UUID.randomUUID(), orderId2, "u2", new BigDecimal("88")));

        assertThat(first.success()).isTrue();
        assertThat(second.success()).isTrue();

        Integer totalEvents = jdbc.queryForObject(
                "SELECT count(*) FROM domain_events WHERE aggregate_id IN (?, ?)",
                Integer.class, orderId, orderId2);
        assertThat(totalEvents).isEqualTo(2);
    }
}
