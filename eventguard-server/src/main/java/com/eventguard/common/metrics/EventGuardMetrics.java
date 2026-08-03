package com.eventguard.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 业务观测指标统一封装（评测模块可观测数据的基础）。
 * <p>
 * 全部方法对 {@link MeterRegistry} 为 null 时降级为空操作（仿 {@code OrderQueryService}
 * 的 null-safe 模式）：单元测试用 {@code new} 直构类、或未启用 actuator 时不会抛异常。
 * <p>
 * 指标命名沿用 {@code eventguard.*} 前缀（与既有 {@code eventguard.projection.lag} 一致），
 * 经 Micrometer 落入 Prometheus 后为 {@code eventguard_*}（counter/timer 展开为
 * {@code _total} / {@code _seconds_bucket} 等）。
 */
@Component
public class EventGuardMetrics {

    private final MeterRegistry registry;

    /** 单元测试 / 无 actuator 上下文：空操作。 */
    public EventGuardMetrics() {
        this.registry = null;
    }

    @Autowired(required = false)
    public EventGuardMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    private boolean enabled() {
        return registry != null;
    }

    /** 计数器 +1。tags 形如 {@code "command","CreateOrderCommand","result","success"}。 */
    public void counter(String name, String... tags) {
        if (enabled()) {
            Counter.builder(name).tags(tags).register(registry).increment();
        }
    }

    /** 计时器（毫秒）。 */
    public void record(String name, long millis, String... tags) {
        if (enabled()) {
            Timer.builder(name).tags(tags).register(registry).record(millis, TimeUnit.MILLISECONDS);
        }
    }

    /** 可变化 gauge（如活动连接数），按 name+tags 幂等注册。 */
    public void gauge(String name, Supplier<Number> supplier, String... tags) {
        if (enabled()) {
            Gauge.builder(name, supplier, s -> s.get().doubleValue()).tags(tags).register(registry);
        }
    }
}
