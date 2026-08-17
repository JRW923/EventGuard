package com.eventguard.site;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 个人主页内容存取（site_profile 单行表）。
 * ponytail: 单行 JSONB 整存整取，前端按约定结构渲染；字段级校验由前端编辑器负责，
 * 升级路径=按板块拆列或独立表。
 */
@Repository
public class SiteProfileRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SiteProfileRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** 读取整份内容 JSON；未配置（空表/畸形 JSON）返回 empty，前端回落静态默认。 */
    public Optional<JsonNode> findContent() {
        String raw = jdbc.query(
                "SELECT content::text FROM site_profile WHERE id = 1",
                (rs, i) -> rs.getString(1)).stream().findFirst().orElse(null);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readTree(raw));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 保存（upsert 单行），updatedBy 记录操作者留审计痕迹。 */
    public void saveContent(JsonNode content, String updatedBy) {
        jdbc.update(
                "INSERT INTO site_profile (id, content, updated_by, updated_at) VALUES (1, ?::jsonb, ?, now()) " +
                        "ON CONFLICT (id) DO UPDATE SET content = EXCLUDED.content, updated_by = EXCLUDED.updated_by, updated_at = now()",
                content.toString(), updatedBy);
    }
}
