package com.eventguard.consistency;

import com.eventguard.command.aggregate.AggregateRepository;
import com.eventguard.command.command.CreateOrderCommand;
import com.eventguard.command.command.PayOrderCommand;
import com.eventguard.command.handler.CommandLogRepository;
import com.eventguard.command.handler.CommandRetryTemplate;
import com.eventguard.command.handler.OrderCommandHandler;
import com.eventguard.common.dto.CommandResult;
import com.eventguard.common.exception.OptimisticConcurrencyException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers 并发一致性测试：
 * 1. 同一订单并发支付：10 线程并发，只有 1 个成功，其余 OCC 失败
 * 2. 事件版本号连续无间隔
 * 3. 聚合根从事件流重建后状态正确
 *
 * 默认跳过（本地无 Docker / 用户指示云端验证）；
 * 在云服务器上用 mvn test -Deventguard.run.integration=true 启用。
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "eventguard.run.integration", matches = "true")
class OrderConsistencyTest {

    @Container
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("eventguard")
            .withUsername("eventguard")
            .withPassword("eventguard");

    static JdbcTemplate jdbc;
    static OrderCommandHandler handler;
    static AggregateRepository aggregateRepository;
    static EventStoreJdbcImpl eventStore;

    @BeforeAll
    static void setup() throws Exception {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(pg.getJdbcUrl());
        ds.setUser(pg.getUsername());
        ds.setPassword(pg.getPassword());
        jdbc = new JdbcTemplate(ds);

        // 执行 DDL
        jdbc.execute(readResource("/db/migration/V1__init.sql"));
        jdbc.execute(readResource("/db/migration/V2__full_schema.sql"));

        ObjectMapper om = new ObjectMapper();
        EventDeserializer deserializer = new EventDeserializer(om);
        eventStore = new EventStoreJdbcImpl(jdbc, om, deserializer);
        SnapshotStoreJdbcImpl snapshotStore = new SnapshotStoreJdbcImpl(jdbc, om);
        aggregateRepository = new AggregateRepository(eventStore, snapshotStore, om);
        CommandLogRepository commandLogRepository = new CommandLogRepository(jdbc, om);
        CommandRetryTemplate retryTemplate = new CommandRetryTemplate();
        handler = new OrderCommandHandler(aggregateRepository, commandLogRepository, retryTemplate,
                new DataSourceTransactionManager(ds));
    }

    private static String readResource(String path) throws Exception {
        try (var is = OrderConsistencyTest.class.getResourceAsStream(path)) {
            assert is != null : "资源不存在: " + path;
            return new String(is.readAllBytes());
        }
    }

    @Test
    void concurrent_payments_same_order_should_only_succeed_once() throws Exception {
        UUID orderId = UUID.randomUUID();
        handler.handle(new CreateOrderCommand(UUID.randomUUID(), orderId, "user-1", new BigDecimal("99.00")));

        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CommandResult>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final String paymentId = "pay-" + i;
            futures.add(executor.submit(() -> {
                start.await();
                try {
                    return handler.handle(new PayOrderCommand(UUID.randomUUID(), orderId, paymentId));
                } catch (OptimisticConcurrencyException e) {
                    return CommandResult.failure("OCC: " + e.getMessage());
                }
            }));
        }

        start.countDown();
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        long successCount = futures.stream().filter(f -> {
            try { return f.get().success(); }
            catch (Exception e) { return false; }
        }).count();

        assertThat(successCount)
                .as("10 个并发支付应该只有 1 个成功")
                .isEqualTo(1);

        // 验证事件版本号连续无间隔
        List<Integer> versions = jdbc.queryForList(
                "SELECT event_version FROM domain_events WHERE aggregate_id = ? ORDER BY event_version",
                Integer.class, orderId);
        assertThat(versions).containsExactly(1, 2);

        // 验证聚合根重建后状态正确
        var agg = aggregateRepository.load(orderId);
        assertThat(agg.getStatus().name()).isEqualTo("PAID");
    }

    @Test
    void concurrent_create_different_orders_should_all_succeed() throws Exception {
        int threads = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CommandResult>> futures = new ArrayList<>();
        AtomicInteger counter = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                start.await();
                UUID orderId = UUID.randomUUID();
                counter.incrementAndGet();
                return handler.handle(new CreateOrderCommand(UUID.randomUUID(), orderId, "u" + counter.get(), new BigDecimal("10")));
            }));
        }

        start.countDown();
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        long successCount = futures.stream().filter(f -> {
            try { return f.get().success(); }
            catch (Exception e) { return false; }
        }).count();

        assertThat(successCount).isEqualTo(5);
    }
}
