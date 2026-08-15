package com.eventguard.compensation.service;

import com.eventguard.command.handler.CompensationCommandHandler;
import com.eventguard.common.dto.CommandResult;
import com.eventguard.compensation.action.CompensationActionRegistry;
import com.eventguard.compensation.action.CompensationAction;
import com.eventguard.compensation.model.CompensationRequest;
import com.eventguard.compensation.model.CompensationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class CompensationServiceTest {

    @Mock
    CompensationCommandHandler commandHandler;

    @Mock
    CompensationActionRegistry registry;

    @Mock
    CompensationAction action;

    @InjectMocks
    CompensationService service;

    @BeforeEach
    void setUp() {
        lenient().when(registry.get(any())).thenReturn(action);
        lenient().when(action.executeResult(any(), any())).thenReturn(CompensationResult.success("done"));
    }

    @Test
    void execute_should_reject_unknown_action_type() {
        CompensationRequest req = new CompensationRequest("UNKNOWN_ACTION", UUID.randomUUID(), Map.of());
        when(registry.isSupported("UNKNOWN_ACTION")).thenReturn(false);

        CompensationResult result = service.execute(req);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("不在白名单");
        verify(commandHandler, never()).handle(any());
    }

    @Test
    void execute_should_dispatch_compensation_and_write_event_for_refund() {
        UUID aggId = UUID.randomUUID();
        CompensationRequest req = new CompensationRequest("REFUND", aggId, Map.of("amount", 100));
        when(registry.isSupported("REFUND")).thenReturn(true);
        when(commandHandler.handle(any())).thenReturn(CommandResult.success(1));

        CompensationResult result = service.execute(req);

        assertThat(result.isSuccess()).isTrue();
        verify(commandHandler, times(1)).handle(any());
    }

    @Test
    void execute_should_support_all_five_whitelist_actions() {
        String[] actions = {"REFUND", "NOTIFY_DELAY", "MARK_OUT_OF_STOCK", "FREEZE_ORDER", "BACKOFF_AND_STOP"};
        for (String action : actions) {
            when(registry.isSupported(action)).thenReturn(true);
            when(commandHandler.handle(any())).thenReturn(CommandResult.success(1));
            CompensationRequest req = new CompensationRequest(action, UUID.randomUUID(), Map.of());
            CompensationResult result = service.execute(req);
            assertThat(result.isSuccess()).as("动作 %s 应成功", action).isTrue();
        }
    }

    @Test
    void execute_should_return_failure_when_command_handler_throws() {
        UUID aggId = UUID.randomUUID();
        CompensationRequest req = new CompensationRequest("REFUND", aggId, Map.of());
        when(registry.isSupported("REFUND")).thenReturn(true);
        doThrow(new RuntimeException("db error")).when(commandHandler).handle(any());

        CompensationResult result = service.execute(req);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("db error");
    }

    @Test
    void execute_should_not_write_completion_event_when_action_fails() {
        CompensationRequest req = new CompensationRequest("REFUND", UUID.randomUUID(), Map.of());
        when(registry.isSupported("REFUND")).thenReturn(true);
        when(action.executeResult(any(), any())).thenReturn(CompensationResult.failure("gateway failed"));

        CompensationResult result = service.execute(req);

        assertThat(result.isSuccess()).isFalse();
        verify(commandHandler, never()).handle(any());
    }
}
