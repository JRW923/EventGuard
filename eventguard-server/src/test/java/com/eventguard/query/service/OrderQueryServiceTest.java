package com.eventguard.query.service;

import com.eventguard.common.exception.ProjectionLagException;
import com.eventguard.query.model.EventDto;
import com.eventguard.query.model.OrderView;
import com.eventguard.query.repository.OrderViewRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderQueryServiceTest {

    @Mock OrderViewRepository orderViewRepository;
    OrderQueryService service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        // 手动构造：@InjectMocks 无法注入 @Value 的 long 基本类型（会得 0），导致 readAfterWrite 立即超时
        service = new OrderQueryService(orderViewRepository, 2000, 50);
    }

    @Test
    void readAfterWrite_should_return_when_version_meets() {
        UUID orderId = UUID.randomUUID();
        OrderView v = new OrderView();
        v.setOrderId(orderId);
        v.setVersion(5);
        when(orderViewRepository.findById(orderId)).thenReturn(Optional.of(v));

        OrderView result = service.readAfterWrite(orderId, 5);

        assertThat(result.getVersion()).isEqualTo(5);
    }

    @Test
    void readAfterWrite_should_return_when_version_exceeds() {
        UUID orderId = UUID.randomUUID();
        OrderView v = new OrderView();
        v.setOrderId(orderId);
        v.setVersion(10);
        when(orderViewRepository.findById(orderId)).thenReturn(Optional.of(v));

        OrderView result = service.readAfterWrite(orderId, 5);

        assertThat(result.getVersion()).isEqualTo(10);
    }

    @Test
    void readAfterWrite_should_throw_when_timeout() {
        UUID orderId = UUID.randomUUID();
        OrderView v = new OrderView();
        v.setOrderId(orderId);
        v.setVersion(1);
        when(orderViewRepository.findById(any())).thenReturn(Optional.of(v));

        OrderQueryService fastService = new OrderQueryService(orderViewRepository, 200, 10);

        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> fastService.readAfterWrite(orderId, 99))
                .isInstanceOf(ProjectionLagException.class);
        long elapsed = System.currentTimeMillis() - start;
        assertThat(elapsed).isGreaterThanOrEqualTo(180);
    }

    @Test
    void readAfterWrite_should_throw_when_order_view_missing() {
        UUID orderId = UUID.randomUUID();
        when(orderViewRepository.findById(any())).thenReturn(Optional.empty());

        OrderQueryService fastService = new OrderQueryService(orderViewRepository, 200, 10);
        assertThatThrownBy(() -> fastService.readAfterWrite(orderId, 1))
                .isInstanceOf(ProjectionLagException.class);
    }

    @Test
    void getEvents_should_filter_by_upToVersion() {
        UUID orderId = UUID.randomUUID();
        when(orderViewRepository.findEventsByAggregateId(orderId, null)).thenReturn(List.of(
                eventAt(1), eventAt(2), eventAt(3), eventAt(4)));
        when(orderViewRepository.findEventsByAggregateId(orderId, 2)).thenReturn(List.of(
                eventAt(1), eventAt(2)));

        List<EventDto> all = service.getEvents(orderId);
        assertThat(all).hasSize(4);

        List<EventDto> replay = service.getEvents(orderId, 2);
        assertThat(replay).hasSize(2);
        assertThat(replay).allMatch(e -> e.getVersion() <= 2);
    }

    @Test
    void readAfterWrite_should_increment_lag_counter_on_timeout() {
        UUID orderId = UUID.randomUUID();
        OrderView v = new OrderView();
        v.setOrderId(orderId);
        v.setVersion(1);
        when(orderViewRepository.findById(any())).thenReturn(Optional.of(v));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OrderQueryService fastService = new OrderQueryService(orderViewRepository, 200, 10, 40, registry, new com.eventguard.query.projection.ProjectionProgressNotifier());

        assertThatThrownBy(() -> fastService.readAfterWrite(orderId, 99))
                .isInstanceOf(ProjectionLagException.class);

        assertThat(registry.counter("eventguard.projection.lag", "result", "timeout").count())
                .isEqualTo(1.0);
    }

    @Test
    void readAfterWrite_should_bound_polling_on_lag() {
        // 新机制：兜底轮询由共享单线程按固定间隔执行（通知即时唤醒为主），次数上限 = 超时/间隔 + 首查
        UUID orderId = UUID.randomUUID();
        when(orderViewRepository.findById(any())).thenReturn(Optional.empty());

        OrderQueryService fastService = new OrderQueryService(orderViewRepository, 200, 10);
        assertThatThrownBy(() -> fastService.readAfterWrite(orderId, 1))
                .isInstanceOf(ProjectionLagException.class);

        verify(orderViewRepository, atMost(25)).findById(orderId);
    }

    @Test
    void readAfterWrite_should_return_lag_when_query_crosses_deadline() {
        UUID orderId = UUID.randomUUID();
        when(orderViewRepository.findById(orderId)).thenAnswer(invocation -> {
            Thread.sleep(30);
            return Optional.empty();
        });

        OrderQueryService fastService = new OrderQueryService(orderViewRepository, 10, 10);
        assertThatThrownBy(() -> fastService.readAfterWrite(orderId, 1))
                .isInstanceOf(ProjectionLagException.class)
                .isNotInstanceOf(IllegalArgumentException.class);
    }

    private static EventDto eventAt(int version) {
        EventDto e = new EventDto();
        e.setVersion(version);
        return e;
    }
}
