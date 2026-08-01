-- 认证与授权表（登录 + RBAC）。
-- 表结构幂等；种子数据（权限/角色/用户）由 AuthDataSeeder 在应用启动时写入。
-- 不参与 Debezium publication（仅 domain_events），无需 CDC。

CREATE TABLE IF NOT EXISTS auth_user (
    id                    BIGSERIAL PRIMARY KEY,
    username              VARCHAR(64)  NOT NULL UNIQUE,
    password_hash         VARCHAR(100) NOT NULL,
    display_name          VARCHAR(64),
    enabled               BOOLEAN      NOT NULL DEFAULT TRUE,
    must_change_password  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS auth_role (
    id           BIGSERIAL PRIMARY KEY,
    code         VARCHAR(64)  NOT NULL UNIQUE,
    name         VARCHAR(64)  NOT NULL,
    description  VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS auth_permission (
    id           BIGSERIAL PRIMARY KEY,
    code         VARCHAR(64)  NOT NULL UNIQUE,
    description  VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS auth_user_role (
    user_id  BIGINT NOT NULL REFERENCES auth_user(id)      ON DELETE CASCADE,
    role_id  BIGINT NOT NULL REFERENCES auth_role(id)      ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE IF NOT EXISTS auth_role_permission (
    role_id        BIGINT NOT NULL REFERENCES auth_role(id)        ON DELETE CASCADE,
    permission_id  BIGINT NOT NULL REFERENCES auth_permission(id)  ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

-- 认证/用户管理审计
CREATE TABLE IF NOT EXISTS auth_audit_log (
    id          BIGSERIAL PRIMARY KEY,
    username    VARCHAR(64),
    action      VARCHAR(64)  NOT NULL,
    detail      VARCHAR(512),
    ip          VARCHAR(64),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
