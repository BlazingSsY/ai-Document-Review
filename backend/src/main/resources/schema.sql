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
    scenario_id     BIGINT          REFERENCES scenarios(id) ON DELETE SET NULL,
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
    -- Structured-output compatibility: auto / json_schema / json_object / prompt_only.
    -- "auto" selects a provider-safe mode and can downgrade when an API rejects
    -- response_format. Existing rows default to auto for backward compatibility.
    response_format_mode VARCHAR(32) NOT NULL DEFAULT 'auto',
    -- 关闭思考时下发哪个参数。none 表示不发。系统不再按模型 id 猜测，
    -- 由配置直接声明，行为可在本表里一眼看清。
    reasoning_control VARCHAR(32)   NOT NULL DEFAULT 'none',
    -- 服务端锁定 temperature 的推理模型勾选，请求里就不带 temperature。
    omit_temperature BOOLEAN        NOT NULL DEFAULT FALSE,
    -- 输出预算下限；为空时按本次注入的检查项数量动态计算。
    output_token_budget INTEGER,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);

-- Rolling migration: add the column for existing databases that pre-date the flag.
ALTER TABLE ai_model_config ADD COLUMN IF NOT EXISTS model_type VARCHAR(32) NOT NULL DEFAULT 'chat';
ALTER TABLE ai_model_config ADD COLUMN IF NOT EXISTS embedding_dimension INTEGER;
-- 已废弃的「思考模式」开关：系统现在对所有模型统一请求关闭思考，关不掉的固定推理模型
-- （R1/Reasoner/*-thinking/o 系列）按模型 id 自动识别，不再需要这一列。存在则丢弃。
ALTER TABLE ai_model_config DROP COLUMN IF EXISTS thinking_mode;
ALTER TABLE ai_model_config ADD COLUMN IF NOT EXISTS response_format_mode VARCHAR(32) NOT NULL DEFAULT 'auto';
-- 显式声明取代按模型 id 推断：思考控制参数、是否省略 temperature、输出预算下限。
ALTER TABLE ai_model_config ADD COLUMN IF NOT EXISTS reasoning_control VARCHAR(32) NOT NULL DEFAULT 'none';
ALTER TABLE ai_model_config ADD COLUMN IF NOT EXISTS omit_temperature BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE ai_model_config ADD COLUMN IF NOT EXISTS output_token_budget INTEGER;
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

CREATE TABLE IF NOT EXISTS sar_scenarios (
    id              BIGSERIAL       PRIMARY KEY,
    name            VARCHAR(255)    NOT NULL,
    description     TEXT,
    creator_id      BIGINT          NOT NULL REFERENCES users(id)
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
    scenario_id     BIGINT          REFERENCES sar_scenarios(id) ON DELETE SET NULL,
    selected_model  VARCHAR(100)    NOT NULL,
    status          VARCHAR(50)     NOT NULL DEFAULT 'PENDING',
    ai_result       JSONB,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    fail_reason     TEXT,
    problem_count   INTEGER
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

CREATE INDEX IF NOT EXISTS idx_sar_rules_creator ON sar_rules(creator_id);
CREATE INDEX IF NOT EXISTS idx_sar_rules_library ON sar_rules(library_id);
CREATE INDEX IF NOT EXISTS idx_sar_scenarios_creator ON sar_scenarios(creator_id);
CREATE INDEX IF NOT EXISTS idx_sar_review_tasks_user ON sar_review_tasks(user_id);
CREATE INDEX IF NOT EXISTS idx_sar_review_tasks_status ON sar_review_tasks(status);
CREATE INDEX IF NOT EXISTS idx_sar_review_audit_logs_task ON sar_review_audit_logs(task_id, created_at);
CREATE INDEX IF NOT EXISTS idx_sar_user_library_assignment_user ON sar_user_library_assignment(user_id);

-- Dashboard list performance: store a scalar problem count per task so the unified
-- task list never has to read/deserialize the large ai_result JSON just to show "问题数".
ALTER TABLE review_tasks     ADD COLUMN IF NOT EXISTS problem_count INTEGER;
ALTER TABLE sar_review_tasks ADD COLUMN IF NOT EXISTS problem_count INTEGER;

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

-- SAR：ai_result.totalIssues 已等于非 Pass 的检查项数。
UPDATE sar_review_tasks SET problem_count = COALESCE((ai_result->>'totalIssues')::int, 0)
WHERE problem_count IS NULL AND ai_result IS NOT NULL;

-- ===== 规则二级文件夹（按规则类型分组 + 启用开关）=====
-- 在「规则库 → 规则」之间增加一层文件夹：用户可按规则类型（通用/磁效应/霉菌…）
-- 建文件夹归类规则，并对整个文件夹启用/停用；停用的文件夹其规则在审查时整组排除。
-- folder_id 可空：NULL = 未分类，恒启用（向后兼容已有规则）。
CREATE TABLE IF NOT EXISTS rule_folders (
    id          BIGSERIAL    PRIMARY KEY,
    library_id  BIGINT       NOT NULL REFERENCES rule_libraries(id) ON DELETE CASCADE,
    name        VARCHAR(200) NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    creator_id  BIGINT,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE TABLE IF NOT EXISTS sar_rule_folders (
    id          BIGSERIAL    PRIMARY KEY,
    library_id  BIGINT       NOT NULL REFERENCES sar_rule_libraries(id) ON DELETE CASCADE,
    name        VARCHAR(200) NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    creator_id  BIGINT,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_rule_folders_library     ON rule_folders(library_id);
CREATE INDEX IF NOT EXISTS idx_sar_rule_folders_library ON sar_rule_folders(library_id);

-- 规则归属文件夹（可空 = 未分类）。删除文件夹时由 service 层把其规则 folder_id 置空，不删规则。
ALTER TABLE rules     ADD COLUMN IF NOT EXISTS folder_id BIGINT;
ALTER TABLE sar_rules ADD COLUMN IF NOT EXISTS folder_id BIGINT;
CREATE INDEX IF NOT EXISTS idx_rules_folder     ON rules(folder_id);
CREATE INDEX IF NOT EXISTS idx_sar_rules_folder ON sar_rules(folder_id);

-- 全文质量检查（基础文字质量审查）开关：每个审查任务可由用户在新建时自由选择是否启用。
-- 默认 TRUE（保持既有行为）。当前仅 CHUNK 全文逐章管线会执行全文质量检查并据此生效。
ALTER TABLE review_tasks     ADD COLUMN IF NOT EXISTS quality_check_enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE sar_review_tasks ADD COLUMN IF NOT EXISTS quality_check_enabled BOOLEAN NOT NULL DEFAULT TRUE;

-- 审查类别（业务域）：一条任务属于哪类审查业务，与「审查方式」(CHUNK/SAR 管线) 正交——
-- 类别回答“审的是什么文件”，管线回答“用什么方法审”。目前只开放环境试验大纲审查，
-- 后续会扩展试验报告审查、可靠性审查等；先落库，避免新增类别时历史任务无从归属。
-- 默认值让存量任务自动归入 ENV_TEST_OUTLINE，这在当前是事实正确的（系统此前只审试验大纲）。
ALTER TABLE review_tasks     ADD COLUMN IF NOT EXISTS review_category VARCHAR(50) NOT NULL DEFAULT 'ENV_TEST_OUTLINE';
ALTER TABLE sar_review_tasks ADD COLUMN IF NOT EXISTS review_category VARCHAR(50) NOT NULL DEFAULT 'ENV_TEST_OUTLINE';
CREATE INDEX IF NOT EXISTS idx_review_tasks_category     ON review_tasks(review_category);
CREATE INDEX IF NOT EXISTS idx_sar_review_tasks_category ON sar_review_tasks(review_category);

-- 删除审查场景时不应被历史审查任务的外键挡住：把 scenario_id 外键改为 ON DELETE SET NULL，
-- 删场景时仅解除任务与场景的关联（置空），保留审查结果历史。对存量库幂等重建该约束。
ALTER TABLE review_tasks     DROP CONSTRAINT IF EXISTS review_tasks_scenario_id_fkey;
ALTER TABLE review_tasks     ADD  CONSTRAINT review_tasks_scenario_id_fkey
    FOREIGN KEY (scenario_id) REFERENCES scenarios(id) ON DELETE SET NULL;
ALTER TABLE sar_review_tasks DROP CONSTRAINT IF EXISTS sar_review_tasks_scenario_id_fkey;
ALTER TABLE sar_review_tasks ADD  CONSTRAINT sar_review_tasks_scenario_id_fkey
    FOREIGN KEY (scenario_id) REFERENCES sar_scenarios(id) ON DELETE SET NULL;

-- Seed supervisor account (password: admin_root)
INSERT INTO users (email, password_hash, name, role)
VALUES ('admin_root', '$2a$10$ETZlQAgiNM5jbwyBXaG5tOcbZjq8g7Fl7DceMfUmajyOI0/4ASDB.', '项目主管', 'supervisor')
ON CONFLICT DO NOTHING;

-- 内置质量规则编号统一：旧 R-BASIC-QUALITY → R-Q（存量库改名，避免与下方新种子重复创建）。
UPDATE rule_checks SET check_code = replace(check_code, 'R-BASIC-QUALITY-', 'R-Q-')
    WHERE check_code LIKE 'R-BASIC-QUALITY-%';
UPDATE rules SET rule_code = 'R-Q' WHERE rule_code = 'R-BASIC-QUALITY';

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
    v_basic_quality_content TEXT :=
'仅审查当前章节的文字表达质量，不审查工程字段完整性、试验项目完整性、设备证书、试验条件、试验程序或标准符合性。
章节内容简短或只有一行不是问题，不得因篇幅短判定不通过。
错别字/标点、语句通顺、术语一致等文字检查项对当前章节始终适用，不得跳过或判不适用，仅就当前章节文字本身审查。

【举证门槛】报出任何文字问题，必须同时给出以下三项，缺一不可，否则一律判 Pass：
① evidence：包含该问题的完整句子，逐字摘录；
② 改正后的完整句子：写出改好之后的原文，而不是“建议修改”“请核实”“统一表述”这类空话；
③ 差异只涉及文字本身：改正前后的工程含义、数值、类别、指代对象必须完全一致。
若改正会改变工程含义（例如把“测试设备”改成“试验设备”，或改掉标准原文的数值格式），
说明这不是文字问题，判 Pass。

【豁免清单】以下情形一律不得报出，判 Pass：
1. 引自标准原文的表述。凡与 RTCA/DO-160G 等被引用标准正文一致的句子、数值格式与标点用法
（如“15，000.3，000 个孢子/平方厘米”），按原文引用处理，不判错别字、不判标点错误、不判语义不明。
2. 脱密占位符。XXX、XXXX、XX 等成串大写 X 是脱密替换后的设备名称或件号，不是漏字、多字或重复词；
同一文档中 XXX 出现在不同位置指代不同对象属正常脱密结果，不得据此判术语不一致。
3. 航空与试验领域的专业术语和固定搭配，例如“直流搭接电阻”“搭接”“驻留时间”“量级”“浸泡”“归零”
“受试设备”“陪试设备”“工作状态1”等，不得判为语序不当、语病或用词错误。是否属专业术语存疑时判 Pass。
4. 章节标题与正文的用词差异。标题写“受试/陪试设备”而正文只列其中一类，属正常编排，不是术语不一致。
5. 大小写、全半角、中英文标点混排、数字格式与单位写法的差异，除非造成语义歧义，否则不报。
6. 同一概念在标准中本就存在多种合规写法（如“第16.6.1.1条”与“16.6.1.1 条”），不判不一致。
7. 承前省略写法。“同上”“同前”“同 13.7.1”“见上节”等是中文技术文档的正常承前引用，
不属于术语、名称或称谓不一致。需要展开时最多写入 suggestion 作为建议，不得判不通过。

【术语一致性的判定方法】术语一致性检查只在同时满足以下三条时才判不通过：
a. 先证明两个词指的是同一个对象：能在本章原文中找到明确的同指关系（同一表格同一列、同一句并列、
或显式定义为同义）；不能证明同指的一律判 Pass。
b. 下列词对是不同概念，不是术语不一致，永远不得报出：
“测试设备”≠“试验设备”（前者用于测量与功能检测，后者用于施加环境应力）；“受试设备”≠“陪试设备”；
“试验程序”≠“试验方法”；“检查”≠“检测”；“校准”≠“校验”≠“检定”。
c. 差异必须发生在同一章节内。引用其他章节时沿用被引用处的原词，不算不一致。

【置信度门控】本规则只在证据确凿时报出：凡需要推测作者意图、需要联系上下文才能成立、
或与上述豁免清单沾边的判断，一律判 Pass。本规则不产生 Review 结果。';
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

    SELECT id INTO v_rule_id FROM rules WHERE rule_code = 'R-Q' LIMIT 1;
    IF v_rule_id IS NULL THEN
        INSERT INTO rules (rule_name, file_type, content, creator_id, library_id,
                           is_valid, rule_code, rule_type, document_type, description)
        VALUES ('基础文字质量审查', 'md', v_basic_quality_content,
                v_creator_id, v_library_id, TRUE, 'R-Q', 'global', '通用',
                '系统内置基础文字质量审查规则，所有章节始终执行。')
        RETURNING id INTO v_rule_id;
    ELSE
        -- 存量库刷新：R-Q 是误报最集中的规则（人工复核 80 条误报中占 12 条），
        -- 新增的举证门槛与豁免清单必须同步到已部署环境，否则只有全新库能拿到。
        UPDATE rules SET content = v_basic_quality_content WHERE id = v_rule_id;
    END IF;

    INSERT INTO rule_checks (rule_id, check_code, check_type, question, pass_criteria,
                             category, evidence_required, display_order, is_active)
    SELECT v_rule_id, 'R-Q-C001', 'other',
           '是否存在错别字、漏字、多字、重复词或明显标点错误',
           '未发现错别字、漏字、多字、重复词或明显标点错误',
           '其他', TRUE, 1, TRUE
    WHERE NOT EXISTS (SELECT 1 FROM rule_checks WHERE check_code = 'R-Q-C001');

    INSERT INTO rule_checks (rule_id, check_code, check_type, question, pass_criteria,
                             category, evidence_required, display_order, is_active)
    SELECT v_rule_id, 'R-Q-C002', 'other',
           '语句是否通顺，是否存在语序不当、语病或明显歧义',
           '语句通顺、语义明确，不存在语序不当、语病或明显歧义',
           '逻辑一致性', TRUE, 2, TRUE
    WHERE NOT EXISTS (SELECT 1 FROM rule_checks WHERE check_code = 'R-Q-C002');

    INSERT INTO rule_checks (rule_id, check_code, check_type, question, pass_criteria,
                             category, evidence_required, display_order, is_active)
    SELECT v_rule_id, 'R-Q-C003', 'other',
           '本章节内指代同一对象的术语、名称和称谓是否一致',
           '本章节内已证明指代同一对象的术语、名称和称谓保持一致；不同概念的近义词（测试设备与试验设备、'
           || '受试设备与陪试设备、校准与校验与检定等）不算不一致；无法证明同指的判通过',
           '术语一致性', TRUE, 3, TRUE
    WHERE NOT EXISTS (SELECT 1 FROM rule_checks WHERE check_code = 'R-Q-C003');

    -- 存量库刷新检查项文案。三条检查项的通过标准原先只是问题的同义反述，没有给出
    -- 判定边界，模型据此把「引自标准原文的数值格式」「脱密占位符 XXX」「直流搭接电阻
    -- 等专业术语」都报成了文字问题。此处把边界写进 pass_criteria，让规则管理界面里
    -- 看到的标准与实际判定口径一致。
    UPDATE rule_checks SET
        question = '是否存在错别字、漏字、多字、重复词或明显标点错误',
        pass_criteria = '未发现错别字、漏字、多字、重复词或明显标点错误。引自标准原文的表述、'
            || '脱密占位符（XXX 等）、专业术语、以及大小写/全半角/数字格式差异，均不计为问题；'
            || '无法写出改正后完整句子的，不计为问题'
    WHERE check_code = 'R-Q-C001';

    UPDATE rule_checks SET
        question = '语句是否通顺，是否存在语序不当、语病或明显歧义',
        pass_criteria = '语句通顺、语义明确。航空与试验领域的专业术语和固定搭配（如“直流搭接电阻”）'
            || '不计为语病；改正会改变工程含义的，不计为文字问题；无法写出改正后完整句子的，不计为问题'
    WHERE check_code = 'R-Q-C002';

    UPDATE rule_checks SET
        question = '本章节内指代同一对象的术语、名称和称谓是否一致',
        pass_criteria = '本章节内已证明指代同一对象的术语、名称和称谓保持一致；不同概念的近义词'
            || '（测试设备与试验设备、受试设备与陪试设备、校准与校验与检定等）不算不一致；'
            || '脱密占位符 XXX 在不同位置指代不同对象不算不一致；无法证明同指的判通过'
    WHERE check_code = 'R-Q-C003';
END $basic_quality_seed$;

-- ============================================================================
-- 成员管理：单位 + 成员档案
--
-- 「成员」就是登录用户，不另立名册表：成员即账号，导入即开通，历史审查任务的
-- user_id 关联天然可用，看板按单位/人员统计不需要额外的成员↔账号映射。
-- ============================================================================

CREATE TABLE IF NOT EXISTS units (
    id          BIGSERIAL       PRIMARY KEY,
    name        VARCHAR(200)    NOT NULL UNIQUE,
    code        VARCHAR(50),
    remark      TEXT,
    created_at  TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP       NOT NULL DEFAULT NOW()
);

-- 成员字段。全部走 ADD COLUMN IF NOT EXISTS，存量库可直接滚动升级。
ALTER TABLE users ADD COLUMN IF NOT EXISTS unit_id   BIGINT REFERENCES units(id) ON DELETE SET NULL;
ALTER TABLE users ADD COLUMN IF NOT EXISTS username  VARCHAR(100);
ALTER TABLE users ADD COLUMN IF NOT EXISTS id_card   VARCHAR(18);
ALTER TABLE users ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN NOT NULL DEFAULT FALSE;

-- 导入的成员没有邮箱（Excel 只给单位/姓名/身份证号/角色），所以 email 必须可空。
-- 存量账号（admin_root 等）仍然带邮箱，继续用邮箱登录，不受影响。
ALTER TABLE users ALTER COLUMN email DROP NOT NULL;

-- 邮箱原本是 NOT NULL UNIQUE。放宽为可空后，普通 UNIQUE 约束在 PostgreSQL 里允许多行
-- NULL，本可沿用；这里改成部分唯一索引只是把「仅对非空邮箱唯一」这个意图写明确。
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_email_key;
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_email
    ON users(email) WHERE email IS NOT NULL;

-- 登录名在单位内唯一：各单位自己排表、各自编号，跨单位重名互不干扰，
-- 登录时先选单位再输用户名即可定位到唯一账号。
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_unit_username
    ON users(unit_id, username) WHERE username IS NOT NULL;

-- 身份证号是成员的唯一编码，跨单位也不允许重复（同一个人不该在两个单位各有一个账号）。
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_id_card
    ON users(id_card) WHERE id_card IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_users_unit ON users(unit_id);

-- 组织树：parent_id 为空的是一级单位；单位管理员的管辖范围为本单位及全部后代单位。
ALTER TABLE units ADD COLUMN IF NOT EXISTS parent_id BIGINT REFERENCES units(id) ON DELETE RESTRICT;
CREATE INDEX IF NOT EXISTS idx_units_parent ON units(parent_id);

-- 统一权限模型首次落库时执行一次兼容迁移：
-- 1) 存量账号保留原先可使用的环境试验大纲审查功能；
-- 2) 存量 admin 原本按角色隐式看到全部规则库，转换为显式分配，避免升级后突然失权；
-- 3) 后续管理员主动撤销权限时不会因 schema.sql 再次执行而被重新授予。
DO $permission_model$
BEGIN
    IF to_regclass('public.user_feature_assignment') IS NULL THEN
        CREATE TABLE user_feature_assignment (
            user_id       BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            feature_code  VARCHAR(64) NOT NULL,
            PRIMARY KEY (user_id, feature_code)
        );

        INSERT INTO user_feature_assignment (user_id, feature_code)
        SELECT id, 'ENV_TEST_OUTLINE_REVIEW'
        FROM users
        ON CONFLICT DO NOTHING;

        INSERT INTO user_library_assignment (user_id, library_id)
        SELECT u.id, lib.id
        FROM users u
        CROSS JOIN rule_libraries lib
        WHERE u.role = 'admin'
          AND lib.name <> '系统内置规则'
        ON CONFLICT DO NOTHING;
    END IF;
END $permission_model$;

-- 内部角色名 supervisor 保持不变以兼容 JWT 与存量数据，显示语义统一为“平台管理员”。
UPDATE users
SET name = '平台管理员'
WHERE email = 'admin_root' AND name = '项目主管';
