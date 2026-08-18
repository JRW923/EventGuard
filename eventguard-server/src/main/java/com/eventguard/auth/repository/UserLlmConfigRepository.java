package com.eventguard.auth.repository;

import com.eventguard.auth.model.UserLlmConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** 用户级 LLM 配置仓库：JdbcTemplate 风格，与 UserRepository 保持一致。 */
@Repository
public class UserLlmConfigRepository {

    private static final RowMapper<UserLlmConfig> MAPPER = (rs, i) -> {
        UserLlmConfig c = new UserLlmConfig();
        c.setUserId(rs.getLong("user_id"));
        c.setProvider(rs.getString("provider"));
        c.setBaseUrl(rs.getString("base_url"));
        c.setApiKeyEnc(rs.getString("api_key_enc"));
        c.setModel(rs.getString("model"));
        c.setMaxTokens(rs.getInt("max_tokens"));
        c.setTemperature(rs.getDouble("temperature"));
        return c;
    };

    private final JdbcTemplate jdbc;

    public UserLlmConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserLlmConfig> findByUserId(long userId) {
        return jdbc.query("SELECT user_id, provider, base_url, api_key_enc, model, max_tokens, temperature "
                        + "FROM user_llm_config WHERE user_id = ?", MAPPER, userId)
                .stream().findFirst();
    }

    public void upsert(UserLlmConfig c) {
        jdbc.update("INSERT INTO user_llm_config(user_id, provider, base_url, api_key_enc, model, max_tokens, temperature) "
                        + "VALUES (?,?,?,?,?,?,?) "
                        + "ON CONFLICT (user_id) DO UPDATE SET "
                        + "provider = EXCLUDED.provider, base_url = EXCLUDED.base_url, "
                        + "api_key_enc = EXCLUDED.api_key_enc, model = EXCLUDED.model, "
                        + "max_tokens = EXCLUDED.max_tokens, temperature = EXCLUDED.temperature, "
                        + "updated_at = now()",
                c.getUserId(), c.getProvider(), c.getBaseUrl(), c.getApiKeyEnc(),
                c.getModel(), c.getMaxTokens(), c.getTemperature());
    }
}
