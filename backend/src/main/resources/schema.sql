-- AI Review System Schema for PostgreSQL

CREATE EXTENSION IF NOT EXISTS vector;

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

CREATE TABLE IF NOT EXISTS review_audit_logs (
    id              BIGSERIAL       PRIMARY KEY,
    task_id         VARCHAR(36)     NOT NULL REFERENCES review_tasks(id) ON DELETE CASCADE,
    user_id         BIGINT          NOT NULL REFERENCES users(id),
    action          VARCHAR(80)     NOT NULL,
    target_type     VARCHAR(80)     NOT NULL,
    target_id       VARCHAR(255)    NOT NULL,
    before_value    JSONB,
    after_value     JSONB,
    comment         TEXT,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS document_blocks (
    id                  BIGSERIAL       PRIMARY KEY,
    task_id             VARCHAR(36)     NOT NULL REFERENCES review_tasks(id) ON DELETE CASCADE,
    block_id            VARCHAR(80)     NOT NULL,
    block_type          VARCHAR(40)     NOT NULL DEFAULT 'paragraph',
    chapter_index       INTEGER         NOT NULL DEFAULT 0,
    block_index         INTEGER         NOT NULL DEFAULT 0,
    section_path        TEXT,
    text_content        TEXT            NOT NULL,
    text_hash           VARCHAR(80)     NOT NULL,
    embedding_model     VARCHAR(100),
    embedding           VECTOR,
    embedding_dimension INTEGER,
    created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    UNIQUE (task_id, block_id)
);

-- Rolling migration from the first RAG implementation, which stored JSON vectors
-- in embedding_vector TEXT and calculated cosine similarity in Java.
ALTER TABLE document_blocks ADD COLUMN IF NOT EXISTS embedding VECTOR;

CREATE INDEX IF NOT EXISTS idx_document_blocks_task ON document_blocks(task_id);
CREATE INDEX IF NOT EXISTS idx_document_blocks_task_chapter ON document_blocks(task_id, chapter_index, block_index);
CREATE INDEX IF NOT EXISTS idx_document_blocks_vector_filter
    ON document_blocks(task_id, embedding_model, embedding_dimension)
    WHERE embedding IS NOT NULL;

CREATE TABLE IF NOT EXISTS ai_model_config (
    id              BIGSERIAL       PRIMARY KEY,
    model_name      VARCHAR(100)    NOT NULL UNIQUE,
    provider        VARCHAR(100)    NOT NULL DEFAULT 'openai',
    model_type      VARCHAR(32)     NOT NULL DEFAULT 'chat',
    model_key       VARCHAR(100)    NOT NULL DEFAULT '',
    api_key         VARCHAR(500)    NOT NULL,
    endpoint        VARCHAR(500)    NOT NULL,
    context_window  INTEGER         NOT NULL DEFAULT 128000,
    max_tokens      INTEGER         NOT NULL DEFAULT 4096,
    embedding_dimension INTEGER,
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
ALTER TABLE ai_model_config ADD COLUMN IF NOT EXISTS model_type VARCHAR(32) NOT NULL DEFAULT 'chat';
ALTER TABLE ai_model_config ADD COLUMN IF NOT EXISTS embedding_dimension INTEGER;
ALTER TABLE ai_model_config ADD COLUMN IF NOT EXISTS thinking_mode BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE ai_model_config ALTER COLUMN timeout SET DEFAULT 180;
UPDATE ai_model_config SET timeout = 180 WHERE timeout = 60;

CREATE TABLE IF NOT EXISTS user_library_assignment (
    user_id         BIGINT          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    library_id      BIGINT          NOT NULL REFERENCES rule_libraries(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, library_id)
);

-- =========================================================================
-- v2 审查管线表（"原子检查 + 证据绑定 + 选择性复核" 引擎）
--
-- 旧引擎把每个切片连同所有命中规则一次性塞给模型自由发挥，导致换模型时错误数
-- 差距极大、且无法追溯。v2 引擎把规则拆成原子 check，每个 (chunk × rule) 单
-- 独调用一次模型，强制 JSON Schema 输出，要求模型给出原文证据 span，后端再
-- 校验、去重、人工复核。下面 5 张表承载该流程的全部状态。
-- =========================================================================

-- 一条 rule 下的原子检查项（一对多）。从 prompts.json 自动迁移生成。
CREATE TABLE IF NOT EXISTS rule_checks (
    id                  BIGSERIAL       PRIMARY KEY,
    rule_id             BIGINT          NOT NULL REFERENCES rules(id) ON DELETE CASCADE,
    -- 全局唯一编码，形如 "G-1-test_unit.must_contain_unit_name"。
    -- 同一 rule 下不能重复；模型必须按此编码回写结果，便于精确去重 & 审计。
    check_code          VARCHAR(160)    NOT NULL,
    -- 枚举：presence / format / consistency / numeric / reference / other
    check_type          VARCHAR(32)     NOT NULL DEFAULT 'presence',
    -- 给模型的判断问题（应是单一是/否问题，避免主观）
    question            TEXT            NOT NULL,
    -- pass 的明确判据，用于消除"清晰、合理"等主观词的歧义
    pass_criteria       TEXT            NOT NULL,
    -- 用于前端 categoryCounts 聚合
    category            VARCHAR(64),
    -- 是否要求模型必须给出原文证据 span（默认 true）
    evidence_required   BOOLEAN         NOT NULL DEFAULT TRUE,
    display_order       INTEGER         NOT NULL DEFAULT 0,
    is_active           BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    UNIQUE (rule_id, check_code)
);
CREATE INDEX IF NOT EXISTS idx_rule_checks_rule ON rule_checks(rule_id);
ALTER TABLE rule_checks DROP COLUMN IF EXISTS fail_severity;

-- 每条 check 的正/反例 few-shot，用于在 prompt 中锚定"什么算 pass / fail"。
CREATE TABLE IF NOT EXISTS rule_check_examples (
    id              BIGSERIAL       PRIMARY KEY,
    check_id        BIGINT          NOT NULL REFERENCES rule_checks(id) ON DELETE CASCADE,
    polarity        VARCHAR(8)      NOT NULL,   -- 'positive' 或 'negative'
    example_text    TEXT            NOT NULL,
    explanation     TEXT,
    display_order   INTEGER         NOT NULL DEFAULT 0,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_rule_check_examples_check ON rule_check_examples(check_id);

-- 单次审查任务的执行计划与统计（与 review_tasks 1:1）。
CREATE TABLE IF NOT EXISTS review_pipelines (
    id                      BIGSERIAL       PRIMARY KEY,
    task_id                 VARCHAR(36)     NOT NULL UNIQUE REFERENCES review_tasks(id) ON DELETE CASCADE,
    total_chunks            INTEGER         NOT NULL DEFAULT 0,
    -- 计划要执行的 (chunk, rule) 对总数；执行完毕后用来计算覆盖率
    total_rule_invocations  INTEGER         NOT NULL DEFAULT 0,
    executed_invocations    INTEGER         NOT NULL DEFAULT 0,
    -- 解析失败 / 模型拒答的 (chunk, rule) 对数；这些不计入 finding 但要展示
    inconclusive_invocations INTEGER        NOT NULL DEFAULT 0,
    stage1_findings         INTEGER         NOT NULL DEFAULT 0,
    -- 二阶段复核确认/驳回计数
    stage2_confirmed        INTEGER         NOT NULL DEFAULT 0,
    stage2_rejected         INTEGER         NOT NULL DEFAULT 0,
    -- 'PLANNED' / 'RECALLING' / 'VERIFYING' / 'DONE' / 'FAILED'
    status                  VARCHAR(16)     NOT NULL DEFAULT 'PLANNED',
    started_at              TIMESTAMP,
    finished_at             TIMESTAMP,
    created_at              TIMESTAMP       NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_review_pipelines_task ON review_pipelines(task_id);

-- 最终展示给前端的 finding 来源（aiResult.allIssues 即从此表渲染）。
CREATE TABLE IF NOT EXISTS review_findings (
    id                  BIGSERIAL       PRIMARY KEY,
    pipeline_id         BIGINT          NOT NULL REFERENCES review_pipelines(id) ON DELETE CASCADE,
    -- 该 finding 首次被发现时所在的切片
    chunk_index         INTEGER         NOT NULL,
    chunk_label         TEXT,
    rule_id             BIGINT          NOT NULL,
    rule_code           VARCHAR(160)    NOT NULL,
    check_id            BIGINT          NOT NULL,
    check_code          VARCHAR(160)    NOT NULL,
    category            VARCHAR(64),
    description         TEXT            NOT NULL,
    suggestion          TEXT,
    -- 模型给出的、必须在原文中能找到的子串
    evidence_span       TEXT            NOT NULL,
    -- 去重用的归一化 span（空白折叠、去标点、转小写）
    normalized_span     TEXT            NOT NULL,
    -- 跨切片合并后所有出现位置: [{"chunkIndex":1,"charOffset":234}, ...]
    occurrences         JSONB,
    stage1_confidence   DOUBLE PRECISION,
    -- 'N/A'（低/中优先级不复核） / 'CONFIRMED' / 'REJECTED' / 'UNCERTAIN'
    stage2_status       VARCHAR(16)     NOT NULL DEFAULT 'N/A',
    stage2_reason       TEXT,
    created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    -- 同一 pipeline 内 (check + 归一化 span) 唯一，天然去重
    UNIQUE (pipeline_id, check_code, normalized_span)
);
CREATE INDEX IF NOT EXISTS idx_review_findings_pipeline ON review_findings(pipeline_id);
ALTER TABLE review_findings DROP COLUMN IF EXISTS severity;

-- 每一次 LLM 调用的完整原始审计（用户已确认接受每任务多几 MB 存储成本）。
-- 用于事后诊断"为什么这两个模型的结果差距大"——直接对比 request/response。
CREATE TABLE IF NOT EXISTS ai_call_logs (
    id              BIGSERIAL       PRIMARY KEY,
    pipeline_id     BIGINT          REFERENCES review_pipelines(id) ON DELETE CASCADE,
    -- 'RECALL' / 'VERIFY' / 'MIGRATION'
    stage           VARCHAR(16)     NOT NULL,
    chunk_index     INTEGER,
    rule_id         BIGINT,
    check_id        BIGINT,
    model_key       VARCHAR(128)    NOT NULL,
    attempt         INTEGER         NOT NULL DEFAULT 1,
    request_body    JSONB           NOT NULL,
    response_body   JSONB,
    parsed_output   JSONB,
    schema_valid    BOOLEAN,
    http_status     INTEGER,
    duration_ms     INTEGER,
    error_message   TEXT,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_ai_call_logs_pipeline ON ai_call_logs(pipeline_id);
CREATE INDEX IF NOT EXISTS idx_ai_call_logs_stage ON ai_call_logs(stage);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_rules_creator ON rules(creator_id);
CREATE INDEX IF NOT EXISTS idx_rules_library ON rules(library_id);
CREATE INDEX IF NOT EXISTS idx_scenarios_creator ON scenarios(creator_id);
CREATE INDEX IF NOT EXISTS idx_review_tasks_user ON review_tasks(user_id);
CREATE INDEX IF NOT EXISTS idx_review_tasks_status ON review_tasks(status);
CREATE INDEX IF NOT EXISTS idx_review_audit_logs_task ON review_audit_logs(task_id, created_at);
CREATE INDEX IF NOT EXISTS idx_ai_model_config_enabled ON ai_model_config(is_enabled);
CREATE INDEX IF NOT EXISTS idx_ai_model_config_type_enabled ON ai_model_config(model_type, is_enabled);
CREATE INDEX IF NOT EXISTS idx_user_library_assignment_user ON user_library_assignment(user_id);

-- Seed supervisor account (password: admin_root)
INSERT INTO users (email, password_hash, name, role)
VALUES ('admin_root', '$2a$10$ETZlQAgiNM5jbwyBXaG5tOcbZjq8g7Fl7DceMfUmajyOI0/4ASDB.', '项目主管', 'supervisor')
ON CONFLICT (email) DO NOTHING;
