-- 用户级 LLM 配置（每个用户配置自己的 provider/base_url/api_key/model，替代进程级 EG_LLM_* 环境默认值）。
-- api_key 以 AES-256-GCM 加密后存 api_key_enc，密钥由 EG_JWT_SECRET 派生，密文为 base64(iv||tag||ciphertext)。
-- 不参与 Debezium publication（仅 domain_events），无需 CDC。

CREATE TABLE IF NOT EXISTS user_llm_config (
    user_id      BIGINT PRIMARY KEY REFERENCES auth_user(id) ON DELETE CASCADE,
    provider     VARCHAR(32)  NOT NULL DEFAULT '',
    base_url     VARCHAR(500) NOT NULL,
    api_key_enc  TEXT NOT NULL DEFAULT '',
    model        VARCHAR(200) NOT NULL,
    max_tokens   INT NOT NULL DEFAULT 2048,
    temperature  DOUBLE PRECISION NOT NULL DEFAULT 0.3,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
