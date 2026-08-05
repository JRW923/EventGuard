package com.eventguard.anomaly.history;

import com.eventguard.anomaly.model.AnomalyAlert;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 最近告警环形缓冲：server 侧保留最近 N 条告警，供前端在 WS 重连后补拉断线期间错过的告警。
 *
 * 内存态、有界（默认 100）。与告警流水线解耦：WS 广播到 0 个会话的告警仍落进缓冲，
 * 重连即可补拉，解决「断线期间告警永久丢失」问题。容量可配 {@code eg.alerts.recent-capacity}。
 */
@Component
public class RecentAlertsBuffer {

    private final int capacity;
    private final Deque<AnomalyAlert> alerts = new ArrayDeque<>();

    public RecentAlertsBuffer(@Value("${eg.alerts.recent-capacity:100}") int capacity) {
        this.capacity = Math.max(capacity, 1);
    }

    /** 新告警放头部（最新在前），超出容量丢弃最旧。 */
    public synchronized void add(AnomalyAlert alert) {
        if (alert == null) {
            return;
        }
        alerts.addFirst(alert);
        while (alerts.size() > capacity) {
            alerts.removeLast();
        }
    }

    /** 最近告警，最新在前；返回拷贝，不影响内部状态。 */
    public synchronized List<AnomalyAlert> recent() {
        return new ArrayList<>(alerts);
    }
}
