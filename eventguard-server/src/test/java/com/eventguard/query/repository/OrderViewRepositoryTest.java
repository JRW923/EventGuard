package com.eventguard.query.repository;

import com.eventguard.query.model.EventDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderViewRepositoryTest {

    @Mock JdbcTemplate jdbc;
    @InjectMocks OrderViewRepository repository;

    @Test
    @SuppressWarnings("unchecked")
    void findEventsByAggregateId_maps_payload_to_map() throws Exception {
        ObjectMapper om = new ObjectMapper();
        JsonNode payloadNode = om.readTree("{\"orderId\":\"abc\",\"amount\":99.5}");

        ResultSet rs = mock(ResultSet.class);
        UUID eventId = UUID.randomUUID();
        UUID aggId = UUID.randomUUID();
        when(rs.getObject("event_id", UUID.class)).thenReturn(eventId);
        when(rs.getObject("aggregate_id", UUID.class)).thenReturn(aggId);
        when(rs.getString("event_type")).thenReturn("OrderCreated");
        when(rs.getInt("event_version")).thenReturn(1);
        when(rs.getObject("payload", JsonNode.class)).thenReturn(payloadNode);
        when(rs.getObject("created_at", Instant.class)).thenReturn(Instant.now());

        ArgumentCaptor<RowMapper<EventDto>> captor = ArgumentCaptor.forClass(RowMapper.class);
        when(jdbc.query(anyString(), captor.capture(), eq(aggId))).thenReturn(List.of(new EventDto()));

        List<EventDto> events = repository.findEventsByAggregateId(aggId);
        assertThat(events).isNotNull().hasSize(1);

        RowMapper<EventDto> mapper = captor.getValue();
        EventDto dto = mapper.mapRow(rs, 0);
        assertThat(dto.getEventId()).isEqualTo(eventId);
        assertThat(dto.getPayload()).isNotNull();
        assertThat(dto.getPayload()).containsEntry("orderId", "abc");
        assertThat(dto.getPayload().get("amount")).isEqualTo(99.5);
    }

    @Test
    @SuppressWarnings("unchecked")
    void findEventsByAggregateId_handles_null_payload() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        UUID aggId = UUID.randomUUID();
        when(rs.getObject("event_id", UUID.class)).thenReturn(UUID.randomUUID());
        when(rs.getObject("aggregate_id", UUID.class)).thenReturn(aggId);
        when(rs.getString("event_type")).thenReturn("OrderCreated");
        when(rs.getInt("event_version")).thenReturn(1);
        when(rs.getObject("payload", JsonNode.class)).thenReturn(null);
        when(rs.getObject("created_at", Instant.class)).thenReturn(Instant.now());

        ArgumentCaptor<RowMapper<EventDto>> captor = ArgumentCaptor.forClass(RowMapper.class);
        when(jdbc.query(anyString(), captor.capture(), eq(aggId))).thenReturn(List.of(new EventDto()));

        repository.findEventsByAggregateId(aggId);
        RowMapper<EventDto> mapper = captor.getValue();
        EventDto dto = mapper.mapRow(rs, 0);
        assertThat(dto.getPayload()).isNull();
    }
}
