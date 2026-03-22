-- AI Review System Schema for PostgreSQL

CREATE TABLE IF NOT EXISTS users (
    id              BIGSERIAL       PRIMARY KEY,
    email           VARCHAR(255)    NOT NULL UNIQUE,
    password_hash   VARCHAR(255)    NOT NULL,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS rules (
    id              BIGSERIAL       PRIMARY KEY,
    rule_name       VARCHAR(255)    NOT NULL,
    file_type       VARCHAR(20)     NOT NULL,
    content         TEXT            NOT NULL,
    creator_id      BIGINT          NOT NULL REFERENCES users(id),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    is_valid        BOOLEAN         NOT NULL DEFAULT TRUE
);

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

CREATE TABLE IF NOT EXISTS review_tasks (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
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
    api_key         VARCHAR(500)    NOT NULL,
    endpoint        VARCHAR(500)    NOT NULL,
    context_window  INTEGER         NOT NULL DEFAULT 128000,
    timeout         INTEGER         NOT NULL DEFAULT 60,
    is_enabled      BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_rules_creator ON rules(creator_id);
CREATE INDEX IF NOT EXISTS idx_scenarios_creator ON scenarios(creator_id);
CREATE INDEX IF NOT EXISTS idx_review_tasks_user ON review_tasks(user_id);
CREATE INDEX IF NOT EXISTS idx_review_tasks_status ON review_tasks(status);
CREATE INDEX IF NOT EXISTS idx_ai_model_config_enabled ON ai_model_config(is_enabled);
