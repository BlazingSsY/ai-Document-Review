# AI 智能文件审查系统

基于多种大语言模型的智能文档审查平台，支持 Word 文档的自动化审查、问题检测与改进建议。系统提供规则管理、场景配置、多模型接入和角色权限控制等功能。

> **三条并列审查管线**：系统提供三条物理隔离、前端二选一的审查管线，各自拥有独立的规则库 / 规则 / 场景 / 任务表：
> - **全文逐章审查（CHUNK）**：按章节切片，每章节一次 AI 调用，命中规则 + 本章节切片 + 引用章节作为上下文。适合通用文档质量审查。
> - **智能召回审查（RAG）**：把文档向量化入 pgvector，按原子检查项做向量召回 + rerank 取证，再分组评估、逐处列违规。适合"逐检查项取证判定"。详见 [RAG_RECALL_TUNING.md](RAG_RECALL_TUNING.md)。
> - **结构化精准审查（SAR）**：在向量化基础上，按「结构 + 词法 + 语义」三路定位每个检查项的预期区域，取整段区域判定；缺失类按"预期位置清单"判 Fail，再做自适应复核 + 跨章一致性。适合 DO-160G/QTP 这类强结构、缺失类为主的检查单。详见 [SAR_PIPELINE.md](SAR_PIPELINE.md)。
>
> 拆分背景见 [RAG_SPLIT_CHOICES.md](RAG_SPLIT_CHOICES.md)。三条管线共享认证、用户管理、模型配置、WebSocket 进度与导出能力。历史任务按 `ai_result.reviewMode`（CHUNK / RAG / SAR）归属，缺失则视为 CHUNK。

## 功能概览

- **三管线文档审查**：CHUNK 全文逐章 / RAG 智能召回 / SAR 结构化精准，前端 Tab 切换；上传 Word 文档（.doc/.docx）自动审查并生成报告
- **规则库管理**：上传 Markdown 或 JSON 格式规则文件，按规则库分类管理（三条管线各一套）；RAG / SAR 侧支持 Excel 检查单导入（`/import-checklist`）拆解为原子检查项
- **审查场景配置**：将多个规则库组合为审查场景，针对不同业务场景灵活配置
- **检查项判定矩阵**：以原子检查项为单位输出三级判定（Pass / Fail / Review）+ 证据 + 缺失项 + 建议，支持人工确认/改判与审计日志
- **多模型支持**：按用途区分 chat / embedding / reranker 模型，接入 OpenAI、Anthropic、Moonshot、百度、阿里、讯飞等主流 AI 厂商，支持自定义厂商
- **实时进度追踪**：WebSocket 实时推送审查进度、日志与结果
- **角色权限控制**：项目主管 / 管理员 / 普通用户三级权限体系
- **结果导出**：检查项判定矩阵 Excel、审查报告 Word（.docx）、审计 JSON
- **用户管理**：用户注册、登录、密码修改、按管线分别分配规则库

## 技术栈

### 后端

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 17 | 运行环境 |
| Spring Boot | 3.2.5 | Web 框架 |
| Spring Security | - | 认证与授权 |
| MyBatis-Plus | 3.5.5 | ORM 框架 |
| PostgreSQL + pgvector | 16 / 0.8.2 | 关系型数据库 + RAG 向量检索（HNSW） |
| JWT (jjwt) | 0.12.5 | Token 认证 |
| Apache POI | 5.2.5 | Word 文档解析 |
| FastJSON2 | 2.0.47 | JSON 处理 |
| WebSocket | - | 实时通信 |

### 前端

| 技术 | 版本 | 用途 |
|------|------|------|
| React | 18.3 | UI 框架 |
| TypeScript | 5.5 | 类型安全 |
| Vite | 5.4 | 构建工具 |
| Ant Design/Material UI | 5.21 | UI 组件库 |
| Zustand | 4.5 | 状态管理 |
| Axios | 1.7 | HTTP 请求 |

### 基础设施

| 技术 | 用途 |
|------|------|
| Docker & Docker Compose | 容器化部署 |
| Nginx | 反向代理与静态资源服务 |

## 项目结构

```
ai-review-system/
├── backend/                          # Spring Boot 后端
│   ├── src/main/java/com/aireview/
│   │   ├── config/                   # 配置类（Security, JWT, WebSocket, MyBatis, Async）
│   │   ├── controller/               # REST 控制器（18个）
│   │   │   ├── AuthController        # 认证：注册、登录、刷新 Token
│   │   │   ├── ReviewController / RagReviewController / SarReviewController # 三管线审查（同构，前缀 /、/rag、/sar）
│   │   │   ├── UnifiedReviewController # 跨管线：合并列表 /all、合并统计 /stats/all、三表试查 /by-id/{id}
│   │   │   ├── ScenarioController / RagScenarioController / SarScenarioController     # 场景（三管线各一套）
│   │   │   ├── RuleController / RagRuleController / SarRuleController # 规则（RAG/SAR 侧含 /import-checklist）
│   │   │   ├── RuleLibraryController / RagRuleLibraryController / SarRuleLibraryController # 规则库（三管线各一套）
│   │   │   ├── AiModelConfigController # 模型配置：增删改查、启停、连接测试、按类型查启用
│   │   │   ├── UserManagementController # 用户管理（主管权限，?mode=RAG|CHUNK|SAR 控库分配）
│   │   │   ├── UserController        # 个人信息、修改密码
│   │   │   └── HealthController      # 健康检查
│   │   ├── entity/                   # 数据实体（31个：11 CHUNK/共享 + 10 RAG + 10 SAR）
│   │   ├── dto/                      # 数据传输对象（18个）
│   │   ├── repository/               # MyBatis Mapper（33个，含 DocumentVectorRepository / SarDocumentVectorRepository 向量检索）
│   │   ├── review/                   # 审查内核：ReviewResultSchema（schema/枚举）、ChunkBatchPlanner·ModelTier（旧批处理，保留未调用）、migration/
│   │   ├── service/                  # 业务逻辑层（20个：ReviewService / RagReviewService / SarReviewService / ChecklistRuleImportService / ReviewExportUtil 等）
│   │   ├── util/                     # 工具类
│   │   │   ├── WordParser            # Word 文档解析（按一级标题切分章节）
│   │   │   ├── ChunkUtils            # 文档切片（CJK 感知 Token 估算）chunkByChapters
│   │   │   ├── RuleParser            # 规则文件解析与四段式提示词构建
│   │   │   ├── RuleMetadata          # 解析 frontmatter/JSON 元数据（rule_code/rule_type/sections/keywords）
│   │   │   ├── RuleDispatcher        # 按章节命中 keywords/sections 选规则（全局/专项/文档级）
│   │   │   ├── ChapterReferenceResolver # 识别"见第X章/参见X条"并附带被引用章节作上下文
│   │   │   ├── MultiRuleParser       # 单文件拆分为多条规则
│   │   │   ├── DocumentSourceMapper  # 解析结果映射为结构化原文（JSON/Markdown/HTML）
│   │   │   └── SecurityUtils         # 安全工具
│   │   └── websocket/                # WebSocket 处理器
│   ├── src/main/resources/
│   │   ├── application.yml           # 应用配置
│   │   └── schema.sql                # 数据库初始化脚本
│   ├── pom.xml                       # Maven 依赖
│   └── Dockerfile                    # 后端镜像构建
│
├── frontend/                         # React 前端
│   ├── src/
│   │   ├── pages/                    # 页面组件
│   │   │   ├── LoginPage             # 登录/注册
│   │   │   ├── DashboardPage         # 工作台（统计、跨管线任务列表、Tab 切换新建审查）
│   │   │   ├── ReviewWorkspacePage   # 审查详情入口（壳）
│   │   │   ├── reviewWorkspace/      # 工作区拆分：components.tsx / helpers.tsx / useReviewWorkspace.ts
│   │   │   ├── ScenarioListPage      # 审查场景管理（接受 reviewMode prop，CHUNK/RAG/SAR 复用）
│   │   │   ├── RuleListPage          # 审查规则管理（接受 reviewMode prop，CHUNK/RAG/SAR 复用）
│   │   │   ├── ModelConfigPage       # 模型配置管理（按 model_type 分字段）
│   │   │   ├── UserManagementPage    # 用户管理（主管权限，Radio 切换 RAG/CHUNK/SAR 库分配）
│   │   │   └── ProfilePage           # 个人中心
│   │   ├── components/               # 公共组件
│   │   │   ├── AppLayout             # 侧边栏布局（智能召回 / 全文逐章 / 结构化精准 三组）
│   │   │   ├── FileUploader          # 文件上传
│   │   │   ├── RuleUploader          # 规则文件上传
│   │   │   └── ProtectedRoute        # 路由鉴权
│   │   ├── api/                      # API 封装：reviews/rules/scenarios + rag*/sar* 镜像 + pipelineApi（按 mode 派发）+ request（Axios 拦截器）
│   │   ├── store/                    # Zustand：authStore / logStore
│   │   ├── styles/                   # global.css / reviewWorkspace.css
│   │   └── utils/                    # constants / websocket（TaskWebSocket 单例）
│   ├── nginx.conf                    # Nginx 配置
│   ├── Dockerfile                    # 前端镜像构建
│   └── package.json
│
├── docker/postgres/Dockerfile        # PostgreSQL 自定义镜像
├── docker-compose.yml                # 容器编排
├── .env                              # 环境变量
├── .gitattributes                    # Git 行尾配置（保证跨平台兼容）
└── .gitignore
```

## 快速开始

### 环境要求

- Docker Desktop（Mac / Windows / Linux）
- 无需本地安装 Java、Node.js、PostgreSQL

### 一键启动

```bash
git clone <repo-url>
cd ai-review-system
docker compose up -d --build
```

首次构建需要下载 Maven 和 npm 依赖，耗时取决于网速。后续构建仅编译源码，约 15 秒完成。

### 访问地址

| 服务 | 地址 |
|------|------|
| 前端界面 | http://localhost:3000 |
| 后端 API | http://localhost:8080 |
| 数据库 | localhost:5432 |

### 默认管理员账号

| 项目 | 值 |
|------|-----|
| 账号 | admin_root |
| 密码 | admin_root |
| 角色 | 项目主管（supervisor） |

## 数据库设计

系统使用 **PostgreSQL 16 + pgvector**（镜像 `pgvector/pgvector:0.8.2-pg16`，启动时 `CREATE EXTENSION vector`），共约 44 张表，分四组（共享 / CHUNK / RAG / SAR）：

**共享表**

| 表名 | 说明 |
|------|------|
| users | 用户表（邮箱、密码哈希、角色） |
| ai_model_config | AI 模型配置（厂商、密钥、端点、参数、`model_type`=chat/embedding/reranker、`embedding_dimension`） |

**CHUNK 管线表**

| 表名 | 说明 |
|------|------|
| rule_libraries / rules | 规则库 / 审查规则（含 `rule_code`、`rule_type` 等元数据列） |
| scenarios / scenario_rule_mapping / scenario_library_mapping | 审查场景及其与规则/规则库的关联 |
| user_library_assignment | 用户-规则库权限分配 |
| review_tasks | 审查任务（UUID 主键、状态、`ai_result` JSONB） |
| review_audit_logs | 人工复核审计（动作、前后 JSON、操作者） |
| rule_checks / rule_check_examples / document_blocks / review_pipelines / review_findings / ai_call_logs | 原子检查项/证据块/管线/明细/调用日志——RAG 拆分后数据已迁到 `rag_*` 同名表，CHUNK 侧保留为空壳避免 schema drift |

**RAG 管线表（`rag_*` 前缀，14 张）**

`rag_rule_libraries` / `rag_rules` / `rag_rule_checks` / `rag_rule_check_examples` / `rag_scenarios` / `rag_scenario_rule_mapping` / `rag_scenario_library_mapping` / `rag_user_library_assignment` / `rag_review_tasks` / `rag_review_audit_logs` / `rag_document_blocks`（原生 `embedding vector` 列 + HNSW 索引）/ `rag_review_pipelines` / `rag_review_findings` / `rag_ai_call_logs`。与 CHUNK 表结构对称、物理隔离；首次启动由 `schema.sql` 末尾的幂等迁移块按 `rule_checks` / `ai_result.reviewMode='rag'` 把历史 RAG 数据搬入。

**SAR 管线表（`sar_*` 前缀，14 张）**

与 `rag_*` 结构对称的全新一套：`sar_rule_libraries` / `sar_rules` / `sar_rule_checks` / `sar_rule_check_examples` / `sar_scenarios` / `sar_scenario_rule_mapping` / `sar_scenario_library_mapping` / `sar_user_library_assignment` / `sar_review_tasks` / `sar_review_audit_logs` / `sar_document_blocks`（原生 `embedding vector` 列 + HNSW 索引）/ `sar_review_pipelines` / `sar_review_findings` / `sar_ai_call_logs`。全新空库、无历史迁移块。

## 核心工作流程

系统有三条管线，用户在前端 Tab / 侧边栏选择进入；后端按管线物理隔离执行。

### CHUNK 管线：全文逐章审查流程

```
上传文档 -> 选择场景+模型 -> 提交任务
                                |
                    WordParser 按一级标题切分章节
                                |
                    ChunkUtils 按 Token 限制切片
                                |
                    RuleDispatcher 为每个切片选出适用规则
                                |
                    逐章节并行审查（每章节 = 一次 AI 调用）
                    单元内容 = 命中规则 + 本章节切片 + 引用到的其他章节切片
                                |
                    单章节失败只标记为可重试切片，不影响其他章节
                                |
                    aggregateResults 跨切片 fingerprint 去重 + 枚举归一化
                                |
                    汇总结果 -> 审查完成
```

### CHUNK 管线细节：逐章节单次调用

审查以**章节切片**为最小单元，每个章节切片对应**一次独立 AI 调用**，调用量与章节数严格 1:1：

1. **每章节单元**：system prompt = `RuleDispatcher` 为该章节命中的规则（四段式结构化提示词）；user message = 本章节切片正文 + 该章节通过 `ChapterReferenceResolver` 识别到的、被引用的其他章节切片（“见第X章 / 参见 4.5 条 / 参考<标题>”等）。被引用章节内容仅作上下文，不在其上套用本次规则。
2. **并发调度**：父线程 `Semaphore(review.parallel.chunk-concurrency)` 控制单任务的章节并发，提交到独立的 `chunkReviewExecutor` 线程池执行；各章节互不影响。
3. **单章节失败隔离**：某章节 AI 调用失败（如 429）只把该切片标记为 `failed/retryable`，写入 `failedChunks`，不触发"拆批重发"之类的放大重试；任务照常完成，用户可在结果页"重审失败切片"单独补审。
4. **收敛参数**：`temperature=0`、`top_p=1`、`max_tokens=8192`、`seed=sha1(taskId+chunkIdx+0)` 取前 8 字节，保证同任务可复现、跨模型可对比。
5. **结构化输出**：OpenAI 兼容协议走 `response_format=json_schema`，Anthropic 走 `tool_use+tool_choice`，强制模型在解码阶段就生成合法 JSON。
6. **文档级综合审查**：所有章节审完后，若场景含 `rule_type=document_specific` 规则，再用"章节目录 + 各章节摘要"跑一次文档级综合调用。

> **历史说明**：旧版曾用 `ChunkBatchPlanner`（按规则签名 + token 预算把多切片打包成批）+ 非思维模型双采样 + 校准波动态调整 + 批失败拆回单切片重发的"批处理 + 采样收敛"方案。该方案在模型提供方限流（HTTP 429）时，会因"双采样翻倍调用 + 批失败瞬间拆成大量单切片重发"形成"越限流越猛打"的失败风暴，导致大量切片审查失败。现已改为上述逐章节单次调用方案；`ChunkBatchPlanner`、`ModelTier`、`RuleParser.buildBatchStructuredSystemPrompt`、`ReviewResultSchema.batchSchema()` 等批处理类已不再被审查管线调用（暂保留代码，无业务影响）。

### RAG 管线：智能召回审查流程

```
上传文档 -> 选择 RAG 场景+chat模型 -> 提交任务
                                |
            WordParser 解析 + 按节点切块（block-max-chars=1800）
                                |
            embedding 模型批量向量化 -> 写入 pgvector + HNSW 索引
                                |
            按场景规则展开"原子检查项"（rag_rule_checks）
                                |
       阶段1：每个检查项各自向量召回(top-k) + rerank 取证据（不耗 chat token）
                                |
       阶段2：按章节把检查项分组装箱，一次调用评估多项（召回优先，缺失即 Fail，逐处列违规）
                                |
            失败项补审一次 -> （可选）两阶段复核只打 CONFIRMED/UNCERTAIN 标签
                                |
            一违规一行写入 allCheckResults -> 审查完成
```

RAG 管线由 `RagReviewService` 实现，embedding / reranker 模型由后端自动取"第一个 enabled 的"，chat 模型由用户选择。证据窗口、分组、复核等参数见 `review.rag.*`，调参细节见 [RAG_RECALL_TUNING.md](RAG_RECALL_TUNING.md)。

### SAR 管线：结构化精准审查流程

```
上传文档 -> 选择 SAR 场景+chat模型 -> 提交任务
                                |
            WordParser 解析 + 切块 + embedding 向量化（pgvector）
                                |
        构建结构索引（按 section_path/章节聚合块）+ 展开原子检查项
                                |
   ① 三路并联路由：结构(applies_to.sections/keywords) + 词法(术语命中) + 语义(向量召回)
      为每个检查项定位"预期区域"，并给出路由置信度
                                |
   ② 区域级取证：取命中区域的整段原文（非碎片/非整章）按区域分组、一次调用评估多项
   ③ 清单式缺失检测：presence 类按"本区域=预期位置，缺失即 Fail"
                                |
   ④ 自适应复核：仅对低置信/定位不确定的非 Pass 项复核（开关 verify.enabled，默认关）
   ⑤ 跨章一致性：抽取关键实体，核对跨章节矛盾（图表号/术语/参数/类别）
                                |
            一违规一行写入 allCheckResults（含 CONSISTENCY 行）-> 审查完成
```

SAR 管线由 `SarReviewService` 实现，复用 RAG 的 pgvector / embedding / rerank 基础设施，叠加「结构路由 + 区域取证 + 清单缺失 + 自适应复核 + 跨章一致性」五个组件。参数见 `review.sar.*`，设计与调参见 [SAR_PIPELINE.md](SAR_PIPELINE.md)。

### 角色权限

| 功能 | 普通用户 | 管理员 | 项目主管 |
|------|---------|--------|---------|
| 提交审查 | Y | Y | Y |
| 查看自己的任务 | Y | Y | Y |
| 管理规则/场景 | - | Y | Y |
| 模型配置 | - | Y | Y |
| 用户管理 | - | - | Y |

## API 接口

所有接口以 `/api/v1` 为前缀，返回统一格式：

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {},
  "timestamp": "2024-01-01T00:00:00"
}
```

| 模块 | 路径 | 说明 |
|------|------|------|
| 认证（共享） | /api/v1/auth/** | 注册、登录、刷新 Token（无需认证） |
| CHUNK 审查 | /api/v1/reviews/** | 提交 `/execute`、任务 CRUD/取消/重审/导出、复核 `check-decisions`、审计、统计 |
| CHUNK 场景/规则/规则库 | /api/v1/scenarios·rules·rule-libraries/** | 增删改查、规则上传 |
| RAG 审查 | /api/v1/rag/reviews/** | 与 CHUNK 审查同构 |
| RAG 场景/规则/规则库 | /api/v1/rag/scenarios·rules·rule-libraries/** | RAG 侧；规则含 Excel 检查单导入 `/import-checklist` |
| SAR 审查 | /api/v1/sar/reviews/** | 与 CHUNK 审查同构（结构化精准管线） |
| SAR 场景/规则/规则库 | /api/v1/sar/scenarios·rules·rule-libraries/** | SAR 侧；规则含 Excel 检查单导入 `/import-checklist` |
| 跨管线 | /api/v1/reviews/all·stats/all·by-id/{taskId}·by-id/{taskId}/sources | 合并列表/统计（?mode=ALL\|CHUNK\|RAG\|SAR）、管线未知时三表试查详情与原文 |
| 模型（共享） | /api/v1/models/** | 配置管理、连接测试、`/enabled?modelType=` 按类型查启用 |
| 用户管理（共享） | /api/v1/admin/users/** | 用户管理（主管权限，库分配带 ?mode=RAG\|CHUNK\|SAR） |
| 个人（共享） | /api/v1/user/** | 个人信息、改密 |
| 健康检查 | /api/health | 容器健康检查 |

## 部署说明

### 跨平台兼容

项目已做好 Mac / Windows / Linux 全平台兼容：

- `.gitattributes` 强制 LF 行尾，避免 Windows CRLF 导致容器内脚本出错
- `.env` 配置 `COMPOSE_CONVERT_WINDOWS_PATHS=1` 兼容 Windows 路径
- 所有 Docker 镜像支持 AMD64（Intel/Windows）和 ARM64（Apple Silicon）

### 停止服务

```bash
docker compose down -t 3
```

### 清除数据重建

```bash
docker compose down -v          # 删除数据卷
docker compose up -d --build    # 重新构建
```

## 配置说明

### 关键配置项（application.yml）

| 配置 | 默认值 | 说明 |
|------|--------|------|
| server.port | 8080 | 后端端口 |
| spring.servlet.multipart.max-file-size | 200MB | 最大上传文件大小 |
| jwt.access-token-expiration | 3600000 | Access Token 有效期（1小时） |
| jwt.refresh-token-expiration | 604800000 | Refresh Token 有效期（7天） |
| review.chunk.max-tokens | 25600 | 每个文档切片最大 Token 数（章节超长时按段落再分） |
| review.chunk.overlap-tokens | 0 | 切片之间的重叠 token 数 |
| review.parallel.chunk-concurrency | 6 | CHUNK 单任务并行审查的章节切片数 |
| review.dispatch.basic-only-max-chapter | 6 | 章节序号 ≤ 该值时只跑基础规则的调度阈值 |
| review.retry.max-attempts | 4 | AI 调用失败重试次数（4xx 立即失败；5xx 指数退避；429 额外尊重 `Retry-After` 头并采用更陡退避，封顶 60s） |
| review.retry.interval-ms | 1000 | 重试初始间隔（5xx 退避 1s→2s→4s；429 退避 2s→4s→8s 并不低于 `Retry-After`） |
| review.rag.* | 见下 | RAG 管线参数：`recall-top-k=30`、`evidence-max-blocks=10`、`max-checks-per-call=8`、`max-evidence-per-call=16`、`check-concurrency=4`、`verify.enabled=false`、`vector-index.*` 等，详见 [RAG_RECALL_TUNING.md](RAG_RECALL_TUNING.md) |
| review.sar.* | 见下 | SAR 管线参数：`recall-top-k=30`、`evidence-max-blocks=10`、`region-max-blocks=14`、`route-confidence-threshold=0.45`、`max-checks-per-call=8`、`verify.enabled=false` / `verify.adaptive=true`、`consistency.enabled=true` 等，详见 [SAR_PIPELINE.md](SAR_PIPELINE.md) |
| review.prompts.path | ./prompts/prompts/prompts.json | v2 提示词迁移源文件 |
| async.core-pool-size / max-pool-size / queue-capacity | 4 / 8 / 100 | 任务级异步线程池（`reviewTaskExecutor`） |

> **收敛常量**（不开放配置，写死在 `ReviewService` 顶部）：`temperature=0`、`top_p=1`、`max_tokens=8192`、规则段 token 预算 `RULE_BUDGET_TOKENS=6000`。每章节单次调用（不再双采样）。这些值是"跨模型可对比"的契约本身，调动一次会让历史 `ai_result` 与新结果失去可比性，因此不放配置文件。

### 审查结果字段说明（CHUNK 管线 `review_tasks.ai_result`）

> RAG 与 SAR 管线的 `ai_result` 字段不同（`allCheckResults` 一违规一行、`retrievalStats` 等）：RAG 见 [RAG_RECALL_TUNING.md](RAG_RECALL_TUNING.md)，SAR 见 [SAR_PIPELINE.md](SAR_PIPELINE.md)（SAR 的 `retrievalStats` 另含 `regionMaxBlocks` / `verifyAdaptive` / `consistencyFindings` 等，并可能有 `check_code=CONSISTENCY` 的跨章一致性行）。三条管线判定枚举都只有三级 **Pass / Fail / Review**（旧的 Partial / N/A 已并入 Review），**不再有 severity**。

顶层字段：

| 字段 | 含义 |
|---|---|
| `totalChunks` / `chunkResults` | 切片总数与逐切片结果 |
| `overallScore` | 平均分（模型未返回 `overall_score` 时不输出） |
| `totalIssues` / `allIssues` | 跨切片 fingerprint 去重后的问题总数与扁平列表（旧问题视图） |
| `allCheckResults` / `totalCheckResults` / `checkStatusCounts` | 检查项判定矩阵（当前主视图）与按 `status`（Pass/Fail/Review）的计数 |
| `categoryCounts` | 按 `category`（格式/完整性/标准符合性/逻辑一致性/术语一致性/其他）的分桶计数 |
| `confidenceCounts` | 按 `confidence`（high/medium/low/needs_review）的分桶计数 |
| `passedRuleCoverage` | 各 `rule_code` 在 `passed_items` 中的命中次数（覆盖率指标） |
| `failedChunks` / `failedChunkCount` | 因 AI 调用失败而保留的切片，可在 UI 中点击重审 |
| `originalSources` / `sourceTextMode` | 重建的原文章节，供前端右侧原文定位/高亮 |
| `modelName` / `modelKey` | 任务使用的模型，用于跨模型对比时归类 |
| `crossModelEligible` | `false` 表示思维模型，不应参与跨模型对比 |
| `samplingStrategy` | 固定为 `single`（逐章节方案每章节单次调用） |

`allIssues[i]` 字段：

| 字段 | 含义 |
|---|---|
| `location` / `description` / `suggestion` / `rule` / `rule_code` / `evidence` | 与 prompt schema 完全一致；`rule_code` 在 manifest 之外的非法编号会被丢弃 |
| `category` | 强制映射到枚举；无法判断 → `其他`（无 severity 字段） |
| `sourceChunk` / `sourceTitle` / `sourceRefs` | 关联到的切片号、章节标题与原文引用，用于前端定位 |
| `fingerprint` | `sha1(归一化location + "|" + rule_code)`，跨切片去重的主键 |
| `confidence` | `single`（单章节单次调用的默认值）；跨章节重复命中同一 fingerprint 时在 `aggregateResults` 中升为 `high` |
| `occurrences` | 跨切片去重后的命中次数 |
