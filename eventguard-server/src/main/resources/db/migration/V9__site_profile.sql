-- 个人主页内容（单行配置表）：管理员可在控制台编辑，个人主页公开读取
-- content 为整份 JSON（前端结构化渲染），updated_by/updated_at 留审计痕迹
CREATE TABLE IF NOT EXISTS site_profile (
    id          INT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    content     JSONB NOT NULL,
    updated_by  VARCHAR(64),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
