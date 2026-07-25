package com.eventguard.query.service;

import com.eventguard.common.exception.ProjectionLagException;
import com.eventguard.query.model.EventDto;
import com.eventguard.query.model.OrderView;
import com.eventguard.query.repository.OrderViewRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 读己写一致性（设计文档 7.2.5）：
 * 命令端返回 expectedVersion，查询端带 version 等待读模型追上，超时抛 ProjectionLagException。
 *
 * V2 监控增强（7.2.5 部分实现补全）：readAfterWrite 记录耗时 Timer 与投影延迟 Counter。
 */
@Service
public class OrderQueryService {

    private final OrderViewRepository orderViewRepository;
    private final long timeoutMs;
    private final long pollIntervalMs;
    // ponytail: MeterRegistry 由 actuator 自动装配；测试/未启用 actuator 时为 null，指标降级为空操作
    private final MeterRegistry meterRegistry;

    public OrderQueryService(OrderViewRepository orderViewRepository,
                             @Value("${eventguard.read-your-writes.timeout-ms:2000}") long timeoutMs,
                             @Value("${eventguard.read-your-writes.poll-interval-ms:50}") long pollIntervalMs) {
        this(orderViewRepository, timeoutMs, pollIntervalMs, null);
    }

    @Autowired
    public OrderQueryService(OrderViewRepository orderViewRepository,
                             @Value("${eventguard.read-your-writes.timeout-ms:2000}") long timeoutMs,
                             @Value("${eventguard.read-your-writes.poll-interval-ms:50}") long pollIntervalMs,
                             MeterRegistry meterRegistry) {
        this.orderViewRepository = orderViewRepository;
        this.timeoutMs = timeoutMs;
        this.pollIntervalMs = pollIntervalMs;
        this.meterRegistry = meterRegistry;
    }

    public OrderView readAfterWrite(UUID orderId, int expectedVersion) {
        long start = System.currentTimeMillis();
        try {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                Optional<OrderView> opt = orderViewRepository.findById(orderId);
                if (opt.isPresent() && opt.get().getVersion() >= expectedVersion) {
                    return opt.get();
                }
                try {
                    Thread.sleep(pollIntervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ProjectionLagException("读模型等待被中断，orderId=" + orderId);
                }
            }
            throw new ProjectionLagException(
                    "读模型未追上，orderId=" + orderId + " expectedVersion=" + expectedVersion);
        } catch (ProjectionLagException e) {
            // 投影延迟（读模型未及时追上）计数：供 /actuator/metrics 观测
            if (meterRegistry != null) {
                meterRegistry.counter("eventguard.projection.lag", "result", "timeout").increment();
            }
            throw e;
        } finally {
            if (meterRegistry != null) {
                Timer.builder("eventguard.read.your.writes.duration")
                        .description("读己写等待耗时(ms)")
                        .register(meterRegistry)
                        .record(System.currentTimeMillis() - start, TimeUnit.MILLISECONDS);
            }
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

    /**
     * 事件时间线（支持按版本回放）：upToVersion 非空时仅返回 version <= upToVersion 的事件，
     * 实现"时间旅行"查看订单在某一版本时的历史（事件时间线编辑器的最小可用形态）。
     */
    public List<EventDto> getEvents(java.util.UUID orderId, Integer upToVersion) {
        List<EventDto> events = orderViewRepository.findEventsByAggregateId(orderId);
        if (upToVersion == null) {
            return events;
        }
        // ponytail: 仅做版本过滤，不对事件重放求状态；完整"状态在版本 N"重建为升级路径
        return events.stream()
                .filter(e -> e.getVersion() <= upToVersion)
                .collect(Collectors.toList());
    }
}
