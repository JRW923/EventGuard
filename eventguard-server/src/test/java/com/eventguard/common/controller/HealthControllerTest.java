package com.eventguard.common.controller;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 健康/版本端点单测。 */
class HealthControllerTest {

    @Test
    void health_returns_up_version_and_db_dependency() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(eq("SELECT 1"), eq(Integer.class))).thenReturn(1);

        HealthController controller = new HealthController(jdbc, "0.1.0-SNAPSHOT", "kafka:9092");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.version").value("0.1.0-SNAPSHOT"))
                .andExpect(jsonPath("$.dependencies.db").value("UP"));
    }
}
