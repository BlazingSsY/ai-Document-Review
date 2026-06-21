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
    start_node_id       VARCHAR(80),
    end_node_id         VARCHAR(80),
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
ALTER TABLE document_blocks ADD COLUMN IF NOT EXISTS start_node_id VARCHAR(80);
ALTER TABLE document_blocks ADD COLUMN IF NOT EXISTS end_node_id VARCHAR(80);

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

-- =========================================================================
-- RAG 侧物理隔离表（"智能召回审查"管线）
--
-- 设计：与上方 chunk 侧（rule_libraries / rules / scenarios / review_tasks /
-- review_audit_logs / document_blocks / rule_checks / rule_check_examples /
-- review_pipelines / review_findings / ai_call_logs）字段一致，但通过 rag_
-- 前缀的独立表名做物理隔离。两侧共享 users 与 ai_model_config。
--
-- 该结构对应"方案 B + 粗粒度物理隔离"决策：用户在前端通过侧边栏二选一进入
-- 「智能召回审查 / 全文逐章审查」，两条管线的规则库 / 规则 / 场景 / 任务
-- 彼此互不可见、互不影响。共享的只有账号体系与模型配置。
-- =========================================================================

CREATE TABLE IF NOT EXISTS rag_rule_libraries (
    id              BIGSERIAL       PRIMARY KEY,
    name            VARCHAR(255)    NOT NULL,
    description     TEXT,
    creator_id      BIGINT          NOT NULL REFERENCES users(id),
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS rag_rules (
    id              BIGSERIAL       PRIMARY KEY,
    rule_name       VARCHAR(255)    NOT NULL,
    file_type       VARCHAR(20)     NOT NULL,
    content         TEXT            NOT NULL,
    creator_id      BIGINT          NOT NULL REFERENCES users(id),
    library_id      BIGINT          REFERENCES rag_rule_libraries(id) ON DELETE CASCADE,
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    is_valid        BOOLEAN         NOT NULL DEFAULT TRUE,
    rule_code       VARCHAR(100),
    rule_type       VARCHAR(40),
    document_type   VARCHAR(100),
    sections        JSONB,
    keywords        JSONB,
    description     TEXT,
    source_file     VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS rag_rule_checks (
    id                  BIGSERIAL       PRIMARY KEY,
    rule_id             BIGINT          NOT NULL REFERENCES rag_rules(id) ON DELETE CASCADE,
    check_code          VARCHAR(160)    NOT NULL,
    check_type          VARCHAR(32)     NOT NULL DEFAULT 'presence',
    question            TEXT            NOT NULL,
    pass_criteria       TEXT            NOT NULL,
    category            VARCHAR(64),
    evidence_required   BOOLEAN         NOT NULL DEFAULT TRUE,
    display_order       INTEGER         NOT NULL DEFAULT 0,
    is_active           BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    UNIQUE (rule_id, check_code)
);
CREATE INDEX IF NOT EXISTS idx_rag_rule_checks_rule ON rag_rule_checks(rule_id);

CREATE TABLE IF NOT EXISTS rag_rule_check_examples (
    id              BIGSERIAL       PRIMARY KEY,
    check_id        BIGINT          NOT NULL REFERENCES rag_rule_checks(id) ON DELETE CASCADE,
    polarity        VARCHAR(8)      NOT NULL,
    example_text    TEXT            NOT NULL,
    explanation     TEXT,
    display_order   INTEGER         NOT NULL DEFAULT 0,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_rag_rule_check_examples_check ON rag_rule_check_examples(check_id);

CREATE TABLE IF NOT EXISTS rag_scenarios (
    id              BIGSERIAL       PRIMARY KEY,
    name            VARCHAR(255)    NOT NULL,
    description     TEXT,
    creator_id      BIGINT          NOT NULL REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS rag_scenario_rule_mapping (
    scenario_id     BIGINT          NOT NULL REFERENCES rag_scenarios(id) ON DELETE CASCADE,
    rule_id         BIGINT          NOT NULL REFERENCES rag_rules(id) ON DELETE CASCADE,
    PRIMARY KEY (scenario_id, rule_id)
);

CREATE TABLE IF NOT EXISTS rag_scenario_library_mapping (
    scenario_id     BIGINT          NOT NULL REFERENCES rag_scenarios(id) ON DELETE CASCADE,
    library_id      BIGINT          NOT NULL REFERENCES rag_rule_libraries(id) ON DELETE CASCADE,
    PRIMARY KEY (scenario_id, library_id)
);

CREATE TABLE IF NOT EXISTS rag_user_library_assignment (
    user_id         BIGINT          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    library_id      BIGINT          NOT NULL REFERENCES rag_rule_libraries(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, library_id)
);

CREATE TABLE IF NOT EXISTS rag_review_tasks (
    id              VARCHAR(36)     PRIMARY KEY,
    user_id         BIGINT          NOT NULL REFERENCES users(id),
    file_name       VARCHAR(500)    NOT NULL,
    file_path       VARCHAR(1000)   NOT NULL,
    scenario_id     BIGINT          REFERENCES rag_scenarios(id),
    selected_model  VARCHAR(100)    NOT NULL,
    status          VARCHAR(50)     NOT NULL DEFAULT 'PENDING',
    ai_result       JSONB,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    fail_reason     TEXT
);

CREATE TABLE IF NOT EXISTS rag_review_audit_logs (
    id              BIGSERIAL       PRIMARY KEY,
    task_id         VARCHAR(36)     NOT NULL REFERENCES rag_review_tasks(id) ON DELETE CASCADE,
    user_id         BIGINT          NOT NULL REFERENCES users(id),
    action          VARCHAR(80)     NOT NULL,
    target_type     VARCHAR(80)     NOT NULL,
    target_id       VARCHAR(255)    NOT NULL,
    before_value    JSONB,
    after_value     JSONB,
    comment         TEXT,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS rag_document_blocks (
    id                  BIGSERIAL       PRIMARY KEY,
    task_id             VARCHAR(36)     NOT NULL REFERENCES rag_review_tasks(id) ON DELETE CASCADE,
    block_id            VARCHAR(80)     NOT NULL,
    block_type          VARCHAR(40)     NOT NULL DEFAULT 'paragraph',
    chapter_index       INTEGER         NOT NULL DEFAULT 0,
    block_index         INTEGER         NOT NULL DEFAULT 0,
    section_path        TEXT,
    start_node_id       VARCHAR(80),
    end_node_id         VARCHAR(80),
    text_content        TEXT            NOT NULL,
    text_hash           VARCHAR(80)     NOT NULL,
    embedding_model     VARCHAR(100),
    embedding           VECTOR,
    embedding_dimension INTEGER,
    created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    UNIQUE (task_id, block_id)
);
ALTER TABLE rag_document_blocks ADD COLUMN IF NOT EXISTS start_node_id VARCHAR(80);
ALTER TABLE rag_document_blocks ADD COLUMN IF NOT EXISTS end_node_id VARCHAR(80);
CREATE INDEX IF NOT EXISTS idx_rag_document_blocks_task ON rag_document_blocks(task_id);
CREATE INDEX IF NOT EXISTS idx_rag_document_blocks_task_chapter
    ON rag_document_blocks(task_id, chapter_index, block_index);
CREATE INDEX IF NOT EXISTS idx_rag_document_blocks_vector_filter
    ON rag_document_blocks(task_id, embedding_model, embedding_dimension)
    WHERE embedding IS NOT NULL;

CREATE TABLE IF NOT EXISTS rag_review_pipelines (
    id                      BIGSERIAL       PRIMARY KEY,
    task_id                 VARCHAR(36)     NOT NULL UNIQUE REFERENCES rag_review_tasks(id) ON DELETE CASCADE,
    total_chunks            INTEGER         NOT NULL DEFAULT 0,
    total_rule_invocations  INTEGER         NOT NULL DEFAULT 0,
    executed_invocations    INTEGER         NOT NULL DEFAULT 0,
    inconclusive_invocations INTEGER        NOT NULL DEFAULT 0,
    stage1_findings         INTEGER         NOT NULL DEFAULT 0,
    stage2_confirmed        INTEGER         NOT NULL DEFAULT 0,
    stage2_rejected         INTEGER         NOT NULL DEFAULT 0,
    status                  VARCHAR(16)     NOT NULL DEFAULT 'PLANNED',
    started_at              TIMESTAMP,
    finished_at             TIMESTAMP,
    created_at              TIMESTAMP       NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_rag_review_pipelines_task ON rag_review_pipelines(task_id);

CREATE TABLE IF NOT EXISTS rag_review_findings (
    id                  BIGSERIAL       PRIMARY KEY,
    pipeline_id         BIGINT          NOT NULL REFERENCES rag_review_pipelines(id) ON DELETE CASCADE,
    chunk_index         INTEGER         NOT NULL,
    chunk_label         TEXT,
    rule_id             BIGINT          NOT NULL,
    rule_code           VARCHAR(160)    NOT NULL,
    check_id            BIGINT          NOT NULL,
    check_code          VARCHAR(160)    NOT NULL,
    category            VARCHAR(64),
    description         TEXT            NOT NULL,
    suggestion          TEXT,
    evidence_span       TEXT            NOT NULL,
    normalized_span     TEXT            NOT NULL,
    occurrences         JSONB,
    stage1_confidence   DOUBLE PRECISION,
    stage2_status       VARCHAR(16)     NOT NULL DEFAULT 'N/A',
    stage2_reason       TEXT,
    created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    UNIQUE (pipeline_id, check_code, normalized_span)
);
CREATE INDEX IF NOT EXISTS idx_rag_review_findings_pipeline ON rag_review_findings(pipeline_id);

CREATE TABLE IF NOT EXISTS rag_ai_call_logs (
    id              BIGSERIAL       PRIMARY KEY,
    pipeline_id     BIGINT          REFERENCES rag_review_pipelines(id) ON DELETE CASCADE,
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
CREATE INDEX IF NOT EXISTS idx_rag_ai_call_logs_pipeline ON rag_ai_call_logs(pipeline_id);
CREATE INDEX IF NOT EXISTS idx_rag_ai_call_logs_stage ON rag_ai_call_logs(stage);

CREATE INDEX IF NOT EXISTS idx_rag_rules_creator ON rag_rules(creator_id);
CREATE INDEX IF NOT EXISTS idx_rag_rules_library ON rag_rules(library_id);
CREATE INDEX IF NOT EXISTS idx_rag_scenarios_creator ON rag_scenarios(creator_id);
CREATE INDEX IF NOT EXISTS idx_rag_review_tasks_user ON rag_review_tasks(user_id);
CREATE INDEX IF NOT EXISTS idx_rag_review_tasks_status ON rag_review_tasks(status);
CREATE INDEX IF NOT EXISTS idx_rag_review_audit_logs_task ON rag_review_audit_logs(task_id, created_at);
CREATE INDEX IF NOT EXISTS idx_rag_user_library_assignment_user ON rag_user_library_assignment(user_id);

-- =========================================================================
-- SAR（结构化精准审查）管线表 —— 与 RAG/CHUNK 物理隔离的第三套审查方案。
-- 结构与 rag_* 对称；全新空库，无历史迁移块。
-- =========================================================================

CREATE TABLE IF NOT EXISTS sar_rule_libraries (
    id              BIGSERIAL       PRIMARY KEY,
    name            VARCHAR(255)    NOT NULL,
    description     TEXT,
    creator_id      BIGINT          NOT NULL REFERENCES users(id),
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS sar_rules (
    id              BIGSERIAL       PRIMARY KEY,
    rule_name       VARCHAR(255)    NOT NULL,
    file_type       VARCHAR(20)     NOT NULL,
    content         TEXT            NOT NULL,
    creator_id      BIGINT          NOT NULL REFERENCES users(id),
    library_id      BIGINT          REFERENCES sar_rule_libraries(id) ON DELETE CASCADE,
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    is_valid        BOOLEAN         NOT NULL DEFAULT TRUE,
    rule_code       VARCHAR(100),
    rule_type       VARCHAR(40),
    document_type   VARCHAR(100),
    sections        JSONB,
    keywords        JSONB,
    description     TEXT,
    source_file     VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS sar_rule_checks (
    id                  BIGSERIAL       PRIMARY KEY,
    rule_id             BIGINT          NOT NULL REFERENCES sar_rules(id) ON DELETE CASCADE,
    check_code          VARCHAR(160)    NOT NULL,
    check_type          VARCHAR(32)     NOT NULL DEFAULT 'presence',
    question            TEXT            NOT NULL,
    pass_criteria       TEXT            NOT NULL,
    category            VARCHAR(64),
    evidence_required   BOOLEAN         NOT NULL DEFAULT TRUE,
    display_order       INTEGER         NOT NULL DEFAULT 0,
    is_active           BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    UNIQUE (rule_id, check_code)
);
CREATE INDEX IF NOT EXISTS idx_sar_rule_checks_rule ON sar_rule_checks(rule_id);

CREATE TABLE IF NOT EXISTS sar_rule_check_examples (
    id              BIGSERIAL       PRIMARY KEY,
    check_id        BIGINT          NOT NULL REFERENCES sar_rule_checks(id) ON DELETE CASCADE,
    polarity        VARCHAR(8)      NOT NULL,
    example_text    TEXT            NOT NULL,
    explanation     TEXT,
    display_order   INTEGER         NOT NULL DEFAULT 0,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_sar_rule_check_examples_check ON sar_rule_check_examples(check_id);

CREATE TABLE IF NOT EXISTS sar_scenarios (
    id              BIGSERIAL       PRIMARY KEY,
    name            VARCHAR(255)    NOT NULL,
    description     TEXT,
    creator_id      BIGINT          NOT NULL REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS sar_scenario_rule_mapping (
    scenario_id     BIGINT          NOT NULL REFERENCES sar_scenarios(id) ON DELETE CASCADE,
    rule_id         BIGINT          NOT NULL REFERENCES sar_rules(id) ON DELETE CASCADE,
    PRIMARY KEY (scenario_id, rule_id)
);

CREATE TABLE IF NOT EXISTS sar_scenario_library_mapping (
    scenario_id     BIGINT          NOT NULL REFERENCES sar_scenarios(id) ON DELETE CASCADE,
    library_id      BIGINT          NOT NULL REFERENCES sar_rule_libraries(id) ON DELETE CASCADE,
    PRIMARY KEY (scenario_id, library_id)
);

CREATE TABLE IF NOT EXISTS sar_user_library_assignment (
    user_id         BIGINT          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    library_id      BIGINT          NOT NULL REFERENCES sar_rule_libraries(id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, library_id)
);

CREATE TABLE IF NOT EXISTS sar_review_tasks (
    id              VARCHAR(36)     PRIMARY KEY,
    user_id         BIGINT          NOT NULL REFERENCES users(id),
    file_name       VARCHAR(500)    NOT NULL,
    file_path       VARCHAR(1000)   NOT NULL,
    scenario_id     BIGINT          REFERENCES sar_scenarios(id),
    selected_model  VARCHAR(100)    NOT NULL,
    status          VARCHAR(50)     NOT NULL DEFAULT 'PENDING',
    ai_result       JSONB,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    fail_reason     TEXT
);

CREATE TABLE IF NOT EXISTS sar_review_audit_logs (
    id              BIGSERIAL       PRIMARY KEY,
    task_id         VARCHAR(36)     NOT NULL REFERENCES sar_review_tasks(id) ON DELETE CASCADE,
    user_id         BIGINT          NOT NULL REFERENCES users(id),
    action          VARCHAR(80)     NOT NULL,
    target_type     VARCHAR(80)     NOT NULL,
    target_id       VARCHAR(255)    NOT NULL,
    before_value    JSONB,
    after_value     JSONB,
    comment         TEXT,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS sar_document_blocks (
    id                  BIGSERIAL       PRIMARY KEY,
    task_id             VARCHAR(36)     NOT NULL REFERENCES sar_review_tasks(id) ON DELETE CASCADE,
    block_id            VARCHAR(80)     NOT NULL,
    block_type          VARCHAR(40)     NOT NULL DEFAULT 'paragraph',
    chapter_index       INTEGER         NOT NULL DEFAULT 0,
    block_index         INTEGER         NOT NULL DEFAULT 0,
    section_path        TEXT,
    start_node_id       VARCHAR(80),
    end_node_id         VARCHAR(80),
    text_content        TEXT            NOT NULL,
    text_hash           VARCHAR(80)     NOT NULL,
    embedding_model     VARCHAR(100),
    embedding           VECTOR,
    embedding_dimension INTEGER,
    created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    UNIQUE (task_id, block_id)
);
CREATE INDEX IF NOT EXISTS idx_sar_document_blocks_task ON sar_document_blocks(task_id);
CREATE INDEX IF NOT EXISTS idx_sar_document_blocks_task_chapter
    ON sar_document_blocks(task_id, chapter_index, block_index);
CREATE INDEX IF NOT EXISTS idx_sar_document_blocks_vector_filter
    ON sar_document_blocks(task_id, embedding_model, embedding_dimension)
    WHERE embedding IS NOT NULL;

CREATE TABLE IF NOT EXISTS sar_review_pipelines (
    id                      BIGSERIAL       PRIMARY KEY,
    task_id                 VARCHAR(36)     NOT NULL UNIQUE REFERENCES sar_review_tasks(id) ON DELETE CASCADE,
    total_chunks            INTEGER         NOT NULL DEFAULT 0,
    total_rule_invocations  INTEGER         NOT NULL DEFAULT 0,
    executed_invocations    INTEGER         NOT NULL DEFAULT 0,
    inconclusive_invocations INTEGER        NOT NULL DEFAULT 0,
    stage1_findings         INTEGER         NOT NULL DEFAULT 0,
    stage2_confirmed        INTEGER         NOT NULL DEFAULT 0,
    stage2_rejected         INTEGER         NOT NULL DEFAULT 0,
    status                  VARCHAR(16)     NOT NULL DEFAULT 'PLANNED',
    started_at              TIMESTAMP,
    finished_at             TIMESTAMP,
    created_at              TIMESTAMP       NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_sar_review_pipelines_task ON sar_review_pipelines(task_id);

CREATE TABLE IF NOT EXISTS sar_review_findings (
    id                  BIGSERIAL       PRIMARY KEY,
    pipeline_id         BIGINT          NOT NULL REFERENCES sar_review_pipelines(id) ON DELETE CASCADE,
    chunk_index         INTEGER         NOT NULL,
    chunk_label         TEXT,
    rule_id             BIGINT          NOT NULL,
    rule_code           VARCHAR(160)    NOT NULL,
    check_id            BIGINT          NOT NULL,
    check_code          VARCHAR(160)    NOT NULL,
    category            VARCHAR(64),
    description         TEXT            NOT NULL,
    suggestion          TEXT,
    evidence_span       TEXT            NOT NULL,
    normalized_span     TEXT            NOT NULL,
    occurrences         JSONB,
    stage1_confidence   DOUBLE PRECISION,
    stage2_status       VARCHAR(16)     NOT NULL DEFAULT 'N/A',
    stage2_reason       TEXT,
    created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    UNIQUE (pipeline_id, check_code, normalized_span)
);
CREATE INDEX IF NOT EXISTS idx_sar_review_findings_pipeline ON sar_review_findings(pipeline_id);

CREATE TABLE IF NOT EXISTS sar_ai_call_logs (
    id              BIGSERIAL       PRIMARY KEY,
    pipeline_id     BIGINT          REFERENCES sar_review_pipelines(id) ON DELETE CASCADE,
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
CREATE INDEX IF NOT EXISTS idx_sar_ai_call_logs_pipeline ON sar_ai_call_logs(pipeline_id);
CREATE INDEX IF NOT EXISTS idx_sar_ai_call_logs_stage ON sar_ai_call_logs(stage);

CREATE INDEX IF NOT EXISTS idx_sar_rules_creator ON sar_rules(creator_id);
CREATE INDEX IF NOT EXISTS idx_sar_rules_library ON sar_rules(library_id);
CREATE INDEX IF NOT EXISTS idx_sar_scenarios_creator ON sar_scenarios(creator_id);
CREATE INDEX IF NOT EXISTS idx_sar_review_tasks_user ON sar_review_tasks(user_id);
CREATE INDEX IF NOT EXISTS idx_sar_review_tasks_status ON sar_review_tasks(status);
CREATE INDEX IF NOT EXISTS idx_sar_review_audit_logs_task ON sar_review_audit_logs(task_id, created_at);
CREATE INDEX IF NOT EXISTS idx_sar_user_library_assignment_user ON sar_user_library_assignment(user_id);

-- =========================================================================
-- RAG SPLIT MIGRATION
--
-- 一次性把"原本走 RAG 管线的历史数据"从共享旧表搬到 rag_* 表，同时保留
-- chunk 管线的数据原地不动。判定规则（按用户决策 #3）：
--   - 任何被 rule_checks 引用的规则 → 视为 RAG 数据
--   - 该规则所在的整个规则库（含其它无 checks 的规则）连同其用户分配 →
--     一并迁到 rag_* 侧，保持库的语义完整
--   - 任何映射到 RAG 库的场景及其全部映射库 → 迁到 rag_* 侧
--   - review_tasks 中 ai_result->>'reviewMode' = 'rag' 的任务及其衍生数据
--     (audit_logs / document_blocks / pipelines / findings / ai_call_logs)
--     → 迁到 rag_* 侧；其余历史任务默认归 CHUNK（决策 #8）
--
-- 幂等性：用 `rag_rule_libraries 是否已有数据`作为哨兵；已迁过就直接 RETURN。
-- =========================================================================

DO $rag_migration$
DECLARE
    v_rag_library_ids BIGINT[] := ARRAY[]::BIGINT[];
    v_rag_scenario_ids BIGINT[] := ARRAY[]::BIGINT[];
    v_rag_task_ids TEXT[] := ARRAY[]::TEXT[];
BEGIN
    -- 哨兵：rag_rule_libraries 非空说明已经迁过（或用户已主动建过 RAG 库），跳过。
    IF EXISTS (SELECT 1 FROM rag_rule_libraries LIMIT 1) THEN
        RAISE NOTICE 'RAG split migration: rag_rule_libraries already populated, skipping';
        RETURN;
    END IF;
    -- 没有 rule_checks 也就没有 RAG 历史数据，无需迁移。
    IF NOT EXISTS (SELECT 1 FROM rule_checks LIMIT 1)
       AND NOT EXISTS (SELECT 1 FROM review_tasks WHERE ai_result->>'reviewMode' = 'rag') THEN
        RAISE NOTICE 'RAG split migration: no rule_checks and no rag tasks, nothing to migrate';
        RETURN;
    END IF;

    RAISE NOTICE 'RAG split migration: starting';

    -- 1) 库级 RAG 候选：任何包含 rule_checks 的规则所在的库
    SELECT COALESCE(ARRAY_AGG(DISTINCT r.library_id) FILTER (WHERE r.library_id IS NOT NULL),
                     ARRAY[]::BIGINT[])
    INTO v_rag_library_ids
    FROM rules r
    WHERE EXISTS (SELECT 1 FROM rule_checks rc WHERE rc.rule_id = r.id);

    -- 2) 场景级 RAG 候选：映射到任意 RAG 库的场景
    SELECT COALESCE(ARRAY_AGG(DISTINCT scenario_id), ARRAY[]::BIGINT[])
    INTO v_rag_scenario_ids
    FROM scenario_library_mapping
    WHERE library_id = ANY(v_rag_library_ids);

    -- 3) 把这些场景关联到的其它库也并入 RAG（哪怕没 checks 也要迁，保住场景完整性）
    v_rag_library_ids := ARRAY(
        SELECT DISTINCT lib_id FROM (
            SELECT unnest(v_rag_library_ids) AS lib_id
            UNION
            SELECT library_id AS lib_id FROM scenario_library_mapping
            WHERE scenario_id = ANY(v_rag_scenario_ids)
        ) merged
    );

    -- 4) RAG 历史任务：ai_result.reviewMode = 'rag'
    SELECT COALESCE(ARRAY_AGG(id), ARRAY[]::TEXT[])
    INTO v_rag_task_ids
    FROM review_tasks
    WHERE ai_result->>'reviewMode' = 'rag';

    -- 4.1) 兜底：RAG 历史任务引用的 scenario_id 一并并入 RAG 场景集合
    --      （防御性：避免规则的 checks 后来被删导致库级判定漏掉这些场景）
    v_rag_scenario_ids := ARRAY(
        SELECT DISTINCT sid FROM (
            SELECT unnest(v_rag_scenario_ids) AS sid
            UNION
            SELECT scenario_id FROM review_tasks
            WHERE id = ANY(v_rag_task_ids) AND scenario_id IS NOT NULL
        ) merged
    );

    -- 4.2) 兜底：扩展进来的场景关联的库一并并入 RAG 库集合
    v_rag_library_ids := ARRAY(
        SELECT DISTINCT lib_id FROM (
            SELECT unnest(v_rag_library_ids) AS lib_id
            UNION
            SELECT library_id FROM scenario_library_mapping
            WHERE scenario_id = ANY(v_rag_scenario_ids)
        ) merged
    );

    RAISE NOTICE 'RAG split migration: % libraries, % scenarios, % tasks to migrate',
        COALESCE(array_length(v_rag_library_ids, 1), 0),
        COALESCE(array_length(v_rag_scenario_ids, 1), 0),
        COALESCE(array_length(v_rag_task_ids, 1), 0);

    -- 5) 复制 libraries / rules / rule_checks / rule_check_examples（保留 id 以维持外键）
    INSERT INTO rag_rule_libraries (id, name, description, creator_id, created_at, updated_at)
    SELECT id, name, description, creator_id, created_at, updated_at
    FROM rule_libraries
    WHERE id = ANY(v_rag_library_ids);

    INSERT INTO rag_rules (id, rule_name, file_type, content, creator_id, library_id,
                           updated_at, is_valid, rule_code, rule_type, document_type,
                           sections, keywords, description, source_file)
    SELECT id, rule_name, file_type, content, creator_id, library_id,
           updated_at, is_valid, rule_code, rule_type, document_type,
           sections, keywords, description, source_file
    FROM rules
    WHERE library_id = ANY(v_rag_library_ids)
       -- 兼容 orphan：rule 没归库但带 rule_checks 也属 RAG 候选
       OR (library_id IS NULL
           AND EXISTS (SELECT 1 FROM rule_checks rc WHERE rc.rule_id = rules.id));

    INSERT INTO rag_rule_checks (id, rule_id, check_code, check_type, question, pass_criteria,
                                  category, evidence_required, display_order, is_active,
                                  created_at, updated_at)
    SELECT rc.id, rc.rule_id, rc.check_code, rc.check_type, rc.question, rc.pass_criteria,
           rc.category, rc.evidence_required, rc.display_order, rc.is_active,
           rc.created_at, rc.updated_at
    FROM rule_checks rc
    WHERE EXISTS (SELECT 1 FROM rag_rules rr WHERE rr.id = rc.rule_id);

    INSERT INTO rag_rule_check_examples (id, check_id, polarity, example_text, explanation,
                                          display_order, created_at)
    SELECT rce.id, rce.check_id, rce.polarity, rce.example_text, rce.explanation,
           rce.display_order, rce.created_at
    FROM rule_check_examples rce
    WHERE EXISTS (SELECT 1 FROM rag_rule_checks rrc WHERE rrc.id = rce.check_id);

    -- 6) 复制 scenarios + 两类 mapping + user_library_assignment
    INSERT INTO rag_scenarios (id, name, description, creator_id)
    SELECT id, name, description, creator_id
    FROM scenarios
    WHERE id = ANY(v_rag_scenario_ids);

    INSERT INTO rag_scenario_library_mapping (scenario_id, library_id)
    SELECT scenario_id, library_id
    FROM scenario_library_mapping
    WHERE scenario_id = ANY(v_rag_scenario_ids);

    INSERT INTO rag_scenario_rule_mapping (scenario_id, rule_id)
    SELECT scenario_id, rule_id
    FROM scenario_rule_mapping
    WHERE scenario_id = ANY(v_rag_scenario_ids);

    INSERT INTO rag_user_library_assignment (user_id, library_id)
    SELECT DISTINCT user_id, library_id
    FROM user_library_assignment
    WHERE library_id = ANY(v_rag_library_ids);

    -- 7) 复制 review_tasks 及其衍生数据
    INSERT INTO rag_review_tasks (id, user_id, file_name, file_path, scenario_id,
                                   selected_model, status, ai_result, created_at,
                                   updated_at, fail_reason)
    SELECT id, user_id, file_name, file_path, scenario_id,
           selected_model, status, ai_result, created_at,
           updated_at, fail_reason
    FROM review_tasks
    WHERE id = ANY(v_rag_task_ids);

    INSERT INTO rag_review_audit_logs (id, task_id, user_id, action, target_type, target_id,
                                        before_value, after_value, comment, created_at)
    SELECT id, task_id, user_id, action, target_type, target_id,
           before_value, after_value, comment, created_at
    FROM review_audit_logs
    WHERE task_id = ANY(v_rag_task_ids);

    INSERT INTO rag_document_blocks (id, task_id, block_id, block_type, chapter_index,
                                      block_index, section_path, start_node_id, end_node_id,
                                      text_content, text_hash,
                                      embedding_model, embedding, embedding_dimension, created_at)
    SELECT id, task_id, block_id, block_type, chapter_index,
           block_index, section_path, start_node_id, end_node_id, text_content, text_hash,
           embedding_model, embedding, embedding_dimension, created_at
    FROM document_blocks
    WHERE task_id = ANY(v_rag_task_ids);

    INSERT INTO rag_review_pipelines (id, task_id, total_chunks, total_rule_invocations,
                                       executed_invocations, inconclusive_invocations,
                                       stage1_findings, stage2_confirmed, stage2_rejected,
                                       status, started_at, finished_at, created_at)
    SELECT id, task_id, total_chunks, total_rule_invocations,
           executed_invocations, inconclusive_invocations,
           stage1_findings, stage2_confirmed, stage2_rejected,
           status, started_at, finished_at, created_at
    FROM review_pipelines
    WHERE task_id = ANY(v_rag_task_ids);

    INSERT INTO rag_review_findings (id, pipeline_id, chunk_index, chunk_label, rule_id, rule_code,
                                      check_id, check_code, category, description, suggestion,
                                      evidence_span, normalized_span, occurrences, stage1_confidence,
                                      stage2_status, stage2_reason, created_at)
    SELECT rf.id, rf.pipeline_id, rf.chunk_index, rf.chunk_label, rf.rule_id, rf.rule_code,
           rf.check_id, rf.check_code, rf.category, rf.description, rf.suggestion,
           rf.evidence_span, rf.normalized_span, rf.occurrences, rf.stage1_confidence,
           rf.stage2_status, rf.stage2_reason, rf.created_at
    FROM review_findings rf
    WHERE EXISTS (SELECT 1 FROM rag_review_pipelines rrp WHERE rrp.id = rf.pipeline_id);

    INSERT INTO rag_ai_call_logs (id, pipeline_id, stage, chunk_index, rule_id, check_id,
                                   model_key, attempt, request_body, response_body, parsed_output,
                                   schema_valid, http_status, duration_ms, error_message, created_at)
    SELECT acl.id, acl.pipeline_id, acl.stage, acl.chunk_index, acl.rule_id, acl.check_id,
           acl.model_key, acl.attempt, acl.request_body, acl.response_body, acl.parsed_output,
           acl.schema_valid, acl.http_status, acl.duration_ms, acl.error_message, acl.created_at
    FROM ai_call_logs acl
    WHERE EXISTS (SELECT 1 FROM rag_review_pipelines rrp WHERE rrp.id = acl.pipeline_id);

    -- 8) 推进 rag_* 序列到 max(id)+1，避免之后插入新行时主键冲突
    PERFORM setval('rag_rule_libraries_id_seq',
        (SELECT COALESCE(MAX(id), 0) + 1 FROM rag_rule_libraries), false);
    PERFORM setval('rag_rules_id_seq',
        (SELECT COALESCE(MAX(id), 0) + 1 FROM rag_rules), false);
    PERFORM setval('rag_rule_checks_id_seq',
        (SELECT COALESCE(MAX(id), 0) + 1 FROM rag_rule_checks), false);
    PERFORM setval('rag_rule_check_examples_id_seq',
        (SELECT COALESCE(MAX(id), 0) + 1 FROM rag_rule_check_examples), false);
    PERFORM setval('rag_scenarios_id_seq',
        (SELECT COALESCE(MAX(id), 0) + 1 FROM rag_scenarios), false);
    PERFORM setval('rag_review_audit_logs_id_seq',
        (SELECT COALESCE(MAX(id), 0) + 1 FROM rag_review_audit_logs), false);
    PERFORM setval('rag_document_blocks_id_seq',
        (SELECT COALESCE(MAX(id), 0) + 1 FROM rag_document_blocks), false);
    PERFORM setval('rag_review_pipelines_id_seq',
        (SELECT COALESCE(MAX(id), 0) + 1 FROM rag_review_pipelines), false);
    PERFORM setval('rag_review_findings_id_seq',
        (SELECT COALESCE(MAX(id), 0) + 1 FROM rag_review_findings), false);
    PERFORM setval('rag_ai_call_logs_id_seq',
        (SELECT COALESCE(MAX(id), 0) + 1 FROM rag_ai_call_logs), false);

    -- 9) 从原表删除已搬走的数据。利用 ON DELETE CASCADE：
    --    DELETE FROM review_tasks 自动级联 audit_logs / document_blocks / pipelines → findings / ai_call_logs
    --    DELETE FROM scenarios 自动级联 scenario_*_mapping
    --    DELETE FROM rules     自动级联 rule_checks → rule_check_examples
    --    DELETE FROM rule_libraries 自动级联 rules → rule_checks → examples
    -- 必须先删已迁移的 RAG 任务，否则 scenario_id 外键会阻止删除场景。
    DELETE FROM review_tasks WHERE id = ANY(v_rag_task_ids);
    -- 历史 chunk 任务可能与 RAG 共用场景。此类场景保留为历史任务元数据，
    -- 只删除已经没有任何任务引用的 RAG 场景。
    DELETE FROM scenarios s
    WHERE s.id = ANY(v_rag_scenario_ids)
      AND NOT EXISTS (
          SELECT 1 FROM review_tasks rt WHERE rt.scenario_id = s.id
      );
    DELETE FROM user_library_assignment WHERE library_id = ANY(v_rag_library_ids);
    DELETE FROM rule_libraries WHERE id = ANY(v_rag_library_ids);
    -- orphan 规则（已在 step 5 一并迁到 rag_rules）也要清出 chunk 侧
    DELETE FROM rules
    WHERE library_id IS NULL
      AND EXISTS (SELECT 1 FROM rag_rules rr WHERE rr.id = rules.id);

    -- 安全清场：chunk 侧设计上不再持有任何 rule_checks / RAG 衍生数据。
    -- 若 FK 级联未生效（极少数情况），用显式 DELETE 兜底，保证收敛状态干净。
    DELETE FROM rule_checks;
    DELETE FROM rule_check_examples;
    DELETE FROM review_pipelines;
    DELETE FROM review_findings;
    DELETE FROM ai_call_logs;
    DELETE FROM document_blocks;

    RAISE NOTICE 'RAG split migration: completed';
END $rag_migration$;

-- Dashboard list performance: store a scalar problem count per task so the unified
-- task list never has to read/deserialize the large ai_result JSON just to show "问题数".
ALTER TABLE review_tasks     ADD COLUMN IF NOT EXISTS problem_count INTEGER;
ALTER TABLE rag_review_tasks ADD COLUMN IF NOT EXISTS problem_count INTEGER;

-- One-time backfill for historical rows (only where still NULL). Mirrors the frontend's
-- count: chunk side = Fail+Review check items (fallback totalIssues); RAG side = totalIssues
-- (which already equals the non-Pass check count). Going-forward rows are set in Java exactly.
UPDATE review_tasks SET problem_count = CASE
        WHEN (COALESCE((ai_result->'checkStatusCounts'->>'Pass')::int, 0)
            + COALESCE((ai_result->'checkStatusCounts'->>'Fail')::int, 0)
            + COALESCE((ai_result->'checkStatusCounts'->>'Review')::int, 0)) > 0
        THEN COALESCE((ai_result->'checkStatusCounts'->>'Fail')::int, 0)
           + COALESCE((ai_result->'checkStatusCounts'->>'Review')::int, 0)
        ELSE COALESCE((ai_result->>'totalIssues')::int, 0)
    END
WHERE problem_count IS NULL AND ai_result IS NOT NULL;

UPDATE rag_review_tasks SET problem_count = COALESCE((ai_result->>'totalIssues')::int, 0)
WHERE problem_count IS NULL AND ai_result IS NOT NULL;

-- Seed supervisor account (password: admin_root)
INSERT INTO users (email, password_hash, name, role)
VALUES ('admin_root', '$2a$10$ETZlQAgiNM5jbwyBXaG5tOcbZjq8g7Fl7DceMfUmajyOI0/4ASDB.', '项目主管', 'supervisor')
ON CONFLICT (email) DO NOTHING;

-- Seed the editable built-in "基础文字质量审查" rule + checks.
-- ReviewService still injects this rule into every chapter prompt and enforces it
-- (禁止 N/A、漏项补齐、不受 token 预算裁剪) in code; sourcing its preface and checks
-- from here just lets users edit the wording/criteria via the rule-management UI.
-- Fully idempotent: each row is inserted only when absent (keyed by name / rule_code /
-- check_code), so re-running schema.sql on every startup is safe and never duplicates.
DO $basic_quality_seed$
DECLARE
    v_creator_id BIGINT;
    v_library_id BIGINT;
    v_rule_id    BIGINT;
BEGIN
    SELECT id INTO v_creator_id FROM users WHERE email = 'admin_root' LIMIT 1;
    IF v_creator_id IS NULL THEN
        RETURN; -- no supervisor account to own the seed; ReviewService falls back to defaults
    END IF;

    SELECT id INTO v_library_id FROM rule_libraries WHERE name = '系统内置规则' LIMIT 1;
    IF v_library_id IS NULL THEN
        INSERT INTO rule_libraries (name, description, creator_id)
        VALUES ('系统内置规则',
                '系统内置、跨规则库始终生效的基础规则；可在此编辑其检查项与说明。',
                v_creator_id)
        RETURNING id INTO v_library_id;
    END IF;

    SELECT id INTO v_rule_id FROM rules WHERE rule_code = 'R-BASIC-QUALITY' LIMIT 1;
    IF v_rule_id IS NULL THEN
        INSERT INTO rules (rule_name, file_type, content, creator_id, library_id,
                           is_valid, rule_code, rule_type, document_type, description)
        VALUES ('基础文字质量审查', 'md',
'仅审查当前章节的文字表达质量，不审查工程字段完整性、试验项目完整性、设备证书、试验条件、试验程序或标准符合性。
章节内容简短或只有一行不是问题，不得因篇幅短判定不通过。
下列检查项对当前章节始终适用，不得跳过；如无法确定请判待复核（Review）。',
                v_creator_id, v_library_id, TRUE, 'R-BASIC-QUALITY', 'global', '通用',
                '系统内置基础文字质量审查规则，所有章节始终执行。')
        RETURNING id INTO v_rule_id;
    END IF;

    INSERT INTO rule_checks (rule_id, check_code, check_type, question, pass_criteria,
                             category, evidence_required, display_order, is_active)
    SELECT v_rule_id, 'R-BASIC-QUALITY-C001', 'other',
           '是否存在错别字、漏字、多字、重复词或明显标点错误',
           '未发现错别字、漏字、多字、重复词或明显标点错误',
           '其他', TRUE, 1, TRUE
    WHERE NOT EXISTS (SELECT 1 FROM rule_checks WHERE check_code = 'R-BASIC-QUALITY-C001');

    INSERT INTO rule_checks (rule_id, check_code, check_type, question, pass_criteria,
                             category, evidence_required, display_order, is_active)
    SELECT v_rule_id, 'R-BASIC-QUALITY-C002', 'other',
           '语句是否通顺，是否存在语序不当、语病或明显歧义',
           '语句通顺、语义明确，不存在语序不当、语病或明显歧义',
           '逻辑一致性', TRUE, 2, TRUE
    WHERE NOT EXISTS (SELECT 1 FROM rule_checks WHERE check_code = 'R-BASIC-QUALITY-C002');

    INSERT INTO rule_checks (rule_id, check_code, check_type, question, pass_criteria,
                             category, evidence_required, display_order, is_active)
    SELECT v_rule_id, 'R-BASIC-QUALITY-C003', 'other',
           '本章节内术语、名称和称谓是否一致',
           '本章节内相同对象的术语、名称和称谓保持一致',
           '术语一致性', TRUE, 3, TRUE
    WHERE NOT EXISTS (SELECT 1 FROM rule_checks WHERE check_code = 'R-BASIC-QUALITY-C003');
END $basic_quality_seed$;
