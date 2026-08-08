package com.eventguard.compensation.controller;

import com.eventguard.compensation.action.CompensationActionRegistry;
import com.eventguard.compensation.model.SagaRequest;
import com.eventguard.compensation.saga.CompensationSaga;
import com.eventguard.compensation.saga.SagaStatus;
import com.eventguard.compensation.service.CompensationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** POST /compensations/saga：校验 + 委托 CompensationSaga 编排（Item 6b）。 */
@ExtendWith(MockitoExtension.class)
class CompensationControllerTest {

    @Mock CompensationService service;
    @Mock CompensationSaga saga;
    @Mock CompensationActionRegistry registry;
    @InjectMocks CompensationController controller;

    private static SagaRequest request(SagaRequest.Step... steps) {
        SagaRequest req = new SagaRequest();
        req.setAggregateId(UUID.randomUUID());
        req.setSteps(List.of(steps));
        return req;
    }

    private static SagaRequest.Step step(String actionType) {
        SagaRequest.Step s = new SagaRequest.Step();
        s.setActionType(actionType);
        s.setParams(Map.of());
        return s;
    }

    @Test
    void startSaga_valid_request_delegates_to_saga() {
        when(registry.isSupported("NOTIFY_DELAY")).thenReturn(true);
        when(saga.start(any(), any())).thenReturn(SagaStatus.AWAITING_APPROVAL);

        ResponseEntity<?> resp = controller.startSaga(request(step("NOTIFY_DELAY")));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertThat(body.get("status")).isEqualTo("AWAITING_APPROVAL");
        verify(saga).start(any(), any());
    }

    @Test
    void startSaga_empty_steps_returns_400() {
        SagaRequest req = new SagaRequest();
        req.setAggregateId(UUID.randomUUID());
        req.setSteps(List.of());

        ResponseEntity<?> resp = controller.startSaga(req);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        verify(saga, never()).start(any(), any());
    }

    @Test
    void startSaga_unknown_action_rejected_by_whitelist() {
        when(registry.isSupported("DROP_DATABASE")).thenReturn(false);

        ResponseEntity<?> resp = controller.startSaga(request(step("DROP_DATABASE")));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertThat(body.get("message")).asString().contains("白名单");
        verify(saga, never()).start(any(), any());
    }

    @Test
    void startSaga_null_aggregate_id_returns_400() {
        SagaRequest req = new SagaRequest();
        req.setAggregateId(null);
        req.setSteps(List.of(step("NOTIFY_DELAY")));

        ResponseEntity<?> resp = controller.startSaga(req);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        verify(saga, never()).start(any(), any());
    }
}
