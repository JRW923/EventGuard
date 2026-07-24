package com.eventguard.query.service;

import com.eventguard.common.exception.ProjectionLagException;
import com.eventguard.query.model.OrderView;
import com.eventguard.query.repository.OrderViewRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 读己写一致性（设计文档 7.2.5）：
 * 命令端返回 expectedVersion，查询端带 version 等待读模型追上，超时抛 ProjectionLagException。
 */
@Service
public class OrderQueryService {

    private final OrderViewRepository orderViewRepository;
    private final long timeoutMs;
    private final long pollIntervalMs;

    public OrderQueryService(OrderViewRepository orderViewRepository,
                             @Value("${eventguard.read-your-writes.timeout-ms:2000}") long timeoutMs,
                             @Value("${eventguard.read-your-writes.poll-interval-ms:50}") long pollIntervalMs) {
        this.orderViewRepository = orderViewRepository;
        this.timeoutMs = timeoutMs;
        this.pollIntervalMs = pollIntervalMs;
    }

    public OrderView readAfterWrite(UUID orderId, int expectedVersion) {
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
    }

    public Optional<OrderView> findById(UUID orderId) {
        return orderViewRepository.findById(orderId);
    }

    public com.eventguard.query.model.OrderListResponse listOrders(String status, int page, int size) {
        return orderViewRepository.list(status, page, size);
    }

    public List<com.eventguard.query.model.EventDto> getEvents(java.util.UUID orderId) {
        return orderViewRepository.findEventsByAggregateId(orderId);
    }
}
