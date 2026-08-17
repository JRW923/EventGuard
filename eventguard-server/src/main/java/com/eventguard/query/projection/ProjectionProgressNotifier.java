package com.eventguard.query.projection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 投影进度通知器：投影事务提交后广播 (orderId, version)，读己写等待者被即时唤醒，
 * 替代每个请求各自 Thread.sleep 轮询占住 Web 线程的旧方案。
 *
 * ponytail: 进程内单实例通知（方法级粗粒度锁，每事件一次加锁，远低于单机投影吞吐上限）；
 * 多副本部署时通知不跨实例，需 Redis Pub/Sub 或等兜底轮询兜住——升级路径见设计文档。
 */
@Component
public class ProjectionProgressNotifier {

    private static final Logger log = LoggerFactory.getLogger(ProjectionProgressNotifier.class);
    private static final int LATEST_CAP = 50_000;

    private record Waiter(int expectedVersion, CompletableFuture<Integer> future) {}

    // 各订单已提交的最新投影版本（LRU 有界：等待注册前恰好提交的窗口靠它立即命中）
    private final Map<UUID, Integer> latest = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Integer> eldest) {
                    return size() > LATEST_CAP;
                }
            });

    private final Map<UUID, Set<Waiter>> waiters = new ConcurrentHashMap<>();

    /** 注册等待：注册时若已达标立即完成，否则等 advance 唤醒（或超时/兜底轮询）。 */
    public synchronized CompletableFuture<Integer> await(UUID orderId, int expectedVersion) {
        Integer v = latest.get(orderId);
        if (v != null && v >= expectedVersion) {
            return CompletableFuture.completedFuture(v);
        }
        CompletableFuture<Integer> future = new CompletableFuture<>();
        waiters.computeIfAbsent(orderId, k -> ConcurrentHashMap.newKeySet())
                .add(new Waiter(expectedVersion, future));
        return future;
    }

    /** 投影事务提交后调用：完成该订单所有已达标（含已超时失效）的等待者。 */
    public synchronized void advance(UUID orderId, int version) {
        latest.put(orderId, version);
        Set<Waiter> ws = waiters.get(orderId);
        if (ws == null || ws.isEmpty()) return;
        ws.removeIf(w -> w.future().isDone()
                || (w.expectedVersion() <= version && w.future().complete(version)));
        if (ws.isEmpty()) waiters.remove(orderId, ws);
        log.trace("[投影通知] order={} version={} 唤醒等待者", orderId, version);
    }
}
