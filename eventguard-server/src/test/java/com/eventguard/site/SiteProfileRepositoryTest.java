package com.eventguard.site;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 最小检查：upsert SQL 形状（ON CONFLICT 单行）+ 畸形 JSON 回落 empty。
 */
@ExtendWith(MockitoExtension.class)
class SiteProfileRepositoryTest {

    @Mock JdbcTemplate jdbc;
    // 手动构造：ObjectMapper 用真实实例（@InjectMocks 会给非 mock 参数传 null，
    // readTree 的 NPE 被 findContent 吞掉、三个解析用例全部假失败）
    SiteProfileRepository repository;

    @BeforeEach
    void setUp() {
        repository = new SiteProfileRepository(jdbc, new ObjectMapper());
    }

    @Test
    void saveContent_upserts_single_row_with_audit_fields() {
        ObjectMapper om = new ObjectMapper();
        JsonNode content = om.createObjectNode().put("name", "测试");

        repository.saveContent(content, "admin");

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), args.capture());
        assertThat(args.getValue()).hasSize(2);
        assertThat(args.getValue()[1]).isEqualTo("admin");
        // 单行 upsert + 审计字段（防退化成多行/丢 updated_by）
        verify(jdbc).update(
                org.mockito.ArgumentMatchers.contains("ON CONFLICT (id) DO UPDATE"),
                org.mockito.ArgumentMatchers.eq(content.toString()),
                org.mockito.ArgumentMatchers.eq("admin"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void findContent_returns_empty_on_malformed_json() {
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of("{bad json"));

        assertThat(repository.findContent()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void findContent_returns_empty_when_no_row() {
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of());

        assertThat(repository.findContent()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void findContent_parses_valid_json() {
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of("{\"name\":\"ok\"}"));

        var content = repository.findContent();
        assertThat(content).isPresent();
        assertThat(content.get().get("name").asText()).isEqualTo("ok");
    }
}
