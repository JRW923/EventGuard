-- P2-16 令牌管理：存量库给 auth_user 补 token_version 列（幂等）。
-- 新装库 V3 已含该列，本迁移仅保证历史库升级时不报错。
ALTER TABLE auth_user ADD COLUMN IF NOT EXISTS token_version INT NOT NULL DEFAULT 0;
