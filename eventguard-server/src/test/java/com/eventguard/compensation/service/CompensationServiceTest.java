package com.eventguard.compensation.service;

import com.eventguard.compensation.action.CompensationActionRegistry;
import com.eventguard.compensation.model.CompensationRequest;
import com.eventguard.compensation.model.CompensationResult;
import com.eventguard.event.store.EventStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompensationServiceTest {

    @Mock
    EventStore eventStore;

    @Mock
    CompensationActionRegistry registry;

    @InjectMocks
    CompensationService service;

    @Test
    void execute_should_reject_unknown_action_type() {
        CompensationRequest req = new CompensationRequest(
                "UNKNOWN_ACTION", UUID.randomUUID(), Map.of());

        when(registry.isSupported("UNKNOWN_ACTION")).thenReturn(false);

        CompensationResult result = service.execute(req);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("不在白名单");
        verify(eventStore, never()).append(any(), any(), anyInt());
    }

    @Test
    void execute_should_dispatch_compensation_and_write_event_for_refund() {
        UUID aggId = UUID.randomUUID();
        CompensationRequest req = new CompensationRequest(
                "REFUND", aggId, Map.of("amount", 100));

        when(registry.isSupported("REFUND")).thenReturn(true);

        CompensationResult result = service.execute(req);

        assertThat(result.isSuccess()).isTrue();
        // 验证写了补偿事件
        verify(eventStore, times(1)).append(eq(aggId), anyList(), anyInt());
    }

    @Test
    void execute_should_support_all_five_whitelist_actions() {
        String[] actions = {"REFUND", "NOTIFY_DELAY", "MARK_OUT_OF_STOCK", "FREEZE_ORDER", "BACKOFF_AND_STOP"};
        for (String action : actions) {
            when(registry.isSupported(action)).thenReturn(true);
            CompensationRequest req = new CompensationRequest(action, UUID.randomUUID(), Map.of());
            CompensationResult result = service.execute(req);
            assertThat(result.isSuccess()).as("动作 %s 应成功", action).isTrue();
        }
    }

    @Test
    void execute_should_return_failure_when_event_store_throws() {
        UUID aggId = UUID.randomUUID();
        CompensationRequest req = new CompensationRequest("REFUND", aggId, Map.of());

        when(registry.isSupported("REFUND")).thenReturn(true);
        doThrow(new RuntimeException("db error")).when(eventStore)
                .append(any(), anyList(), anyInt());

        CompensationResult result = service.execute(req);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("db error");
    }
}
