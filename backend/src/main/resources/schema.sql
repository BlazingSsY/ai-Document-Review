-- AI Review System Schema for PostgreSQL

CREATE TABLE IF NOT EXISTS users (
    id              BIGSERIAL       PRIMARY KEY,
    email           VARCHAR(255)    NOT NULL UNIQUE,
    password_hash   VARCHAR(255)    NOT NULL,
    name            VARCHAR(100),
    role            VARCHAR(20)     NOT NULL DEFAULT 'user',
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS rule_libraries (
    id              BIGSERIAL       PRIMARY KEY,
    name            VARCHAR(255)    NOT NULL,
    description     TEXT,
    creator_id      BIGINT          NOT NULL REFERENCES users(id),
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS rules (
    id              BIGSERIAL       PRIMARY KEY,
    rule_name       VARCHAR(255)    NOT NULL,
    file_type       VARCHAR(20)     NOT NULL,
    content         TEXT            NOT NULL,
    creator_id      BIGINT          NOT NULL REFERENCES users(id),
    library_id      BIGINT          REFERENCES rule_libraries(id) ON DELETE CASCADE,
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    is_valid        BOOLEAN         NOT NULL DEFAULT TRUE,
    -- Editable metadata: filled in from content frontmatter on upload, can be
    -- overridden by the user via the rule edit modal.
    rule_code       VARCHAR(100),
    rule_type       VARCHAR(40),
    document_type   VARCHAR(100),
    sections        JSONB,
    keywords        JSONB,
    description     TEXT,
    source_file     VARCHAR(255)
);

-- Rolling migration: add metadata columns if upgrading from a pre-metadata schema.
ALTER TABLE rules ADD COLUMN IF NOT EXISTS rule_code     VARCHAR(100);
ALTER TABLE rules ADD COLUMN IF NOT EXISTS rule_type     VARCHAR(40);
ALTER TABLE rules ADD COLUMN IF NOT EXISTS document_type VARCHAR(100);
ALTER TABLE rules ADD COLUMN IF NOT EXISTS sections      JSONB;
ALTER TABLE rules ADD COLUMN IF NOT EXISTS keywords      JSONB;
ALTER TABLE rules ADD COLUMN IF NOT EXISTS description   TEXT;
ALTER TABLE rules ADD COLUMN IF NOT EXISTS source_file   VARCHAR(255);

-- 删除已废弃的 standard（适用标准）列，存在则丢弃；不存在直接跳过。
ALTER TABLE rules DROP COLUMN IF EXISTS standard;
-- 删除已废弃的 severity（严重程度）列，存在则丢弃；不存在直接跳过。
ALTER TABLE rules DROP COLUMN IF EXISTS severity;

CREATE TABLE IF NOT EXISTS scenarios (
    id              BIGSERIAL       PRIMARY KEY,
    name            VARCHAR(255)    NOT NULL,
    description     TEXT,
    creator_id      BIGINT          NOT NULL REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS scenario_rule_mapping (
    scenario_id     BIGINT          NOT NULL REFERENCES scenarios(id) ON DELETE CASCADE,
    rule_id         BIGINT          NOT NULL REFERENCES rules(id) ON DELETE CASCADE,
    PRIMARY KEY (scenario_id, rule_id)
);

CREATE TABLE IF NOT EXISTS scenario_library_mapping (
    scenario_id     BIGINT          NOT NULL REFERENCES scenarios(id) ON DELETE CASCADE,
    library_id      BIGINT          NOT NULL REFERENCES rule_libraries(id) ON DELETE CASCADE,
    PRIMARY KEY (scenario_id, library_id)
);

CREATE TABLE IF NOT EXISTS review_tasks (
    id              VARCHAR(36)     PRIMARY KEY,
    user_id         BIGINT          NOT NULL REFERENCES users(id),
    file_name       VARCHAR(500)    NOT NULL,
    file_path       VARCHAR(1000)   NOT NULL,
    scenario_id     BIGINT          REFERENCES scenarios(id),
    selected_model  VARCHAR(100)    NOT NULL,
    status          VARCHAR(50)     NOT NULL DEFAULT 'PENDING',
    ai_result       JSONB,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    fail_reason     TEXT
);

CREATE TABLE IF NOT EXISTS ai_model_config (
    id              BIGSERIAL       PRIMARY KEY,
    model_name      VARCHAR(100)    NOT NULL UNIQUE,
    provider        VARCHAR(100)    NOT NULL DEFAULT 'openai',
    model_key       VARCHAR(100)    NOT NULL DEFAULT '',
    api_key         VARCHAR(500)    NOT NULL,
    endpoint        VARCHAR(500)    NOT NULL,
    context_window  INTEGER         NOT NULL DEFAULT 128000,
    max_tokens      INTEGER         NOT NULL DEFAULT 4096,
    temperature     DECIMAL(3,2)    NOT NULL DEFAULT 0.70,
    timeout         INTEGER         NOT NULL DEFAULT 180,
    is_enabled      BOOLEAN         NOT NULL DEFAULT TRUE,
    -- Thinking-mode models (Kimi K2.6, GLM-5.x, ...) fix temperature server-side and
    -- need a much larger max_tokens budget for the chain-of-thought. When true the
    -- backend omits temperature from the API call and ensures max_tokens >= 16000.
    thinking_mode   BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);

-- Rolling migration: add the column for existing databases that pre-date the flag.
ALTER TABLE ai_model_config ADD COLUMN IF NOT EXISTS thinking_mode BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE ai_model_config ALTER COLUMN timeout SET DEFAULT 180;
UPDATE ai_model_config SET timeout = 180 WHERE timeout = 60;

CREATE TABLE IF NOT EXISTS user_library_assignment (
    user_id         BIGINT          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    library_id      BIGINT          NOT NULL REFERENCES rule_libraries(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, library_id)
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_rules_creator ON rules(creator_id);
CREATE INDEX IF NOT EXISTS idx_rules_library ON rules(library_id);
CREATE INDEX IF NOT EXISTS idx_scenarios_creator ON scenarios(creator_id);
CREATE INDEX IF NOT EXISTS idx_review_tasks_user ON review_tasks(user_id);
CREATE INDEX IF NOT EXISTS idx_review_tasks_status ON review_tasks(status);
CREATE INDEX IF NOT EXISTS idx_ai_model_config_enabled ON ai_model_config(is_enabled);
CREATE INDEX IF NOT EXISTS idx_user_library_assignment_user ON user_library_assignment(user_id);

-- Seed supervisor account (password: admin_root)
INSERT INTO users (email, password_hash, name, role)
VALUES ('admin_root', '$2a$10$ETZlQAgiNM5jbwyBXaG5tOcbZjq8g7Fl7DceMfUmajyOI0/4ASDB.', '项目主管', 'supervisor')
ON CONFLICT (email) DO NOTHING;
