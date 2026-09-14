package com.eventguard.common.controller;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 健康/版本端点单测。 */
class HealthControllerTest {

    private HealthController controllerWith(JdbcTemplate jdbc, boolean kafkaUp) {
        HealthController c = spy(new HealthController(jdbc, "0.1.0-SNAPSHOT", "kafka:9092"));
        doReturn(kafkaUp).when(c).pingKafka();
        return c;
    }

    @Test
    void health_returns_up_when_db_and_kafka_are_up() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(eq("SELECT 1"), eq(Integer.class))).thenReturn(1);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controllerWith(jdbc, true)).build();

        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.version").value("0.1.0-SNAPSHOT"))
                .andExpect(jsonPath("$.dependencies.db").value("UP"))
                .andExpect(jsonPath("$.dependencies.kafka").value("UP"));
    }

    @Test
    void health_returns_down_when_kafka_unreachable() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(eq("SELECT 1"), eq(Integer.class))).thenReturn(1);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controllerWith(jdbc, false)).build();

        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.dependencies.db").value("UP"))
                .andExpect(jsonPath("$.dependencies.kafka").value("DOWN"));
    }
}
