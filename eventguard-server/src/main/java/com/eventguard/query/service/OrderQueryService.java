package com.eventguard.query.service;

import com.eventguard.common.exception.ProjectionLagException;
import com.eventguard.query.model.EventDto;
import com.eventguard.query.model.OrderView;
import com.eventguard.query.projection.ProjectionProgressNotifier;
import com.eventguard.query.repository.OrderViewRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 读己写一致性（设计文档 7.2.5）：
 * 命令端返回 expectedVersion，查询端带 version 等待读模型追上，超时抛 ProjectionLagException。
 *
 * 等待机制：投影提交通知（ProjectionProgressNotifier）即时唤醒为主，共享单线程调度器
 * 按 pollInterval 兜底轮询为辅（通知丢失/测试环境无投影时仍能收敛）——Web 线程全程不阻塞，
 * Controller 层以 DeferredResult 返回。
 */
@Service
public class OrderQueryService {

    private final OrderViewRepository orderViewRepository;
    private final long timeoutMs;
    private final long pollIntervalMs;
    private final long maxPollIntervalMs;
    private final ProjectionProgressNotifier notifier;
    // ponytail: 全局共享单线程兜底轮询（每次一个主键查询，通知正常时多数任务在被执行前已被取消）
    private static final ScheduledExecutorService POLL_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ryw-poll");
                t.setDaemon(true);
                return t;
            });
    // ponytail: MeterRegistry 由 actuator 自动装配；测试/未启用 actuator 时为 null，指标降级为空操作
    private final MeterRegistry meterRegistry;

    public OrderQueryService(OrderViewRepository orderViewRepository,
                             @Value("${eventguard.read-your-writes.timeout-ms:2000}") long timeoutMs,
                             @Value("${eventguard.read-your-writes.poll-interval-ms:50}") long pollIntervalMs) {
        this(orderViewRepository, timeoutMs, pollIntervalMs, 200, null, new ProjectionProgressNotifier());
    }

    @Autowired
    public OrderQueryService(OrderViewRepository orderViewRepository,
                             @Value("${eventguard.read-your-writes.timeout-ms:2000}") long timeoutMs,
                             @Value("${eventguard.read-your-writes.poll-interval-ms:50}") long pollIntervalMs,
                             @Value("${eventguard.read-your-writes.max-poll-interval-ms:200}") long maxPollIntervalMs,
                             MeterRegistry meterRegistry,
                             ProjectionProgressNotifier notifier) {
        this.orderViewRepository = orderViewRepository;
        this.timeoutMs = timeoutMs;
        this.pollIntervalMs = Math.max(1, pollIntervalMs);
        this.maxPollIntervalMs = Math.max(this.pollIntervalMs, maxPollIntervalMs);
        this.meterRegistry = meterRegistry;
        this.notifier = notifier;
    }

    /** 异步读己写：投影通知/兜底轮询任一先追平即完成，超时以 TimeoutException 异常完成。 */
    public CompletableFuture<OrderView> readAfterWriteAsync(UUID orderId, int expectedVersion) {
        long start = System.currentTimeMillis();
        Optional<OrderView> first = orderViewRepository.findById(orderId);
        if (first.isPresent() && first.get().getVersion() >= expectedVersion) {
            recordDuration(start);
            return CompletableFuture.completedFuture(first.get());
        }

        CompletableFuture<Integer> wait = notifier.await(orderId, expectedVersion)
                .orTimeout(timeoutMs, TimeUnit.MILLISECONDS);
        // 兜底轮询：通知未到时由共享线程检查读模型并借 advance 唤醒（与通知同一完成路径）
        var pollTask = POLL_SCHEDULER.scheduleAtFixedRate(() -> {
            orderViewRepository.findById(orderId).ifPresent(ov -> {
                if (ov.getVersion() >= expectedVersion) notifier.advance(orderId, ov.getVersion());
            });
        }, pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS);
        wait.whenComplete((v, ex) -> pollTask.cancel(false));

        return wait.thenCompose(v -> {
            OrderView ov = orderViewRepository.findById(orderId)
                    .orElseThrow(() -> new ProjectionLagException("读模型行缺失，orderId=" + orderId));
            if (ov.getVersion() < expectedVersion) {
                // 防御：通知版本达标但行版本未到（理论不可达，投影版本单调）
                throw new ProjectionLagException("读模型未追上，orderId=" + orderId
                        + " expectedVersion=" + expectedVersion + " actual=" + ov.getVersion());
            }
            return CompletableFuture.completedFuture(ov);
        }).whenComplete((v, ex) -> {
            if (ex != null) recordTimeout();
            recordDuration(start);
        });
    }

    /** 同步版（测试与内部调用）：阻塞等待异步结果，超时/异常统一转 ProjectionLagException。 */
    public OrderView readAfterWrite(UUID orderId, int expectedVersion) {
        try {
            return readAfterWriteAsync(orderId, expectedVersion).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof ProjectionLagException ple) throw ple;
            throw new ProjectionLagException(
                    "读模型未追上，orderId=" + orderId + " expectedVersion=" + expectedVersion
                            + "（" + cause.getClass().getSimpleName() + "）");
        }
    }

    private void recordTimeout() {
        if (meterRegistry != null) {
            meterRegistry.counter("eventguard.projection.lag", "result", "timeout").increment();
        }
    }

    private void recordDuration(long start) {
        if (meterRegistry != null) {
            Timer.builder("eventguard.read.your.writes.duration")
                    .description("读己写等待耗时(ms)")
                    .register(meterRegistry)
                    .record(System.currentTimeMillis() - start, TimeUnit.MILLISECONDS);
        }
    }

    public Optional<OrderView> findById(UUID orderId) {
        return orderViewRepository.findById(orderId);
    }

    public com.eventguard.query.model.OrderListResponse listOrders(String status, int page, int size) {
        return orderViewRepository.list(status, page, size);
    }

    public List<EventDto> getEvents(java.util.UUID orderId) {
        return getEvents(orderId, null);
    }

    public List<EventDto> getEvents(java.util.UUID orderId, Integer upToVersion) {
        // upToVersion 在 SQL 层截断（时间旅行）；完整"状态在版本 N"重建为升级路径
        return orderViewRepository.findEventsByAggregateId(orderId, upToVersion);
    }
}
