# AI 智能文件审查系统

面向**环境试验大纲**的 AI 文档审查平台。上传 Word 文档，系统按配置的规则库自动逐章审查，输出「检查项判定矩阵」（Pass / Fail / Review + 证据 + 建议），支持人工复核、审计追溯与多格式导出。

> **当前可用审查管线：CHUNK（全文逐章审查）**
>
> 按章节切片，每章一次 AI 调用，注入命中规则 + 本章正文 + 被引用章节作上下文。
>
> **SAR（结构化精准审查）管线的后端实现完整保留**（`SarReviewService`、`sar_*` 表、`/api/v1/sar/**` 端点、前端 `sarRules`/`sarScenarios`/`sarReviews` API 层），但**前端入口当前已关闭**：`App.tsx` 将 `sar/scenarios`、`sar/rules` 重定向到 `/chunk/*`，侧边栏只保留「全文逐章审查」，新建审查不再提供管线切换。历史任务仍按 `reviewMode` 字段派发读取，缺失则视为 CHUNK。SAR 的设计与调参见 [SAR_PIPELINE.md](SAR_PIPELINE.md)。

## 功能概览

- **逐章文档审查**：上传 Word（.doc/.docx）自动切章、按规则调度、并行审查并生成报告
- **规则三级管理**：规则库 → 文件夹 → 规则；文件夹可整组启用/停用
- **多来源规则导入**：Markdown 按 `##` 拆多条规则、Rule JSON 拆原子检查项、Excel 检查单导入（SAR 侧 `/import-checklist`）
- **增量 upsert 上传**：重复上传时按同一规则库/文件夹内的 `rule_code` 优先匹配、规则名称兜底覆盖，新增规则直接追加，不按 `source_file` 整批删除旧规则
- **规则元信息维护**：名称、编号、类型、适用章节、关键词、描述可在 UI 编辑；仅改元信息不刷新排序时间
- **审查场景配置**：将多个规则库组合为场景，按业务场景灵活切换
- **检查项判定矩阵**：以规则/原子检查项为单位输出三级判定（Pass / Fail / Review）+ 证据 + 缺失项 + 建议；强制每条上传业务规则至少返回一条判定；内置 `R-Q` 文字质量检查仅在 Fail/Review 时显示
- **人工复核**：确认、改判、驳回系统判定，记录完整审计日志（操作人、时间、前后值），结果实时同步到导出文件
- **原文编辑与修订稿**：可在工作区修订原文片段，`DocumentRevisionWriter` 导出带修订的 Word
- **多模型支持**：按用途区分 chat / embedding / reranker，接入 OpenAI、Anthropic、Moonshot、百度、阿里、讯飞等厂商，支持连通性测试与思考模式建议
- **实时进度追踪**：WebSocket 推送审查进度、日志与结果，跨页面持久化
- **成员与权限**：单位组织树 + 三级角色 + 功能码授权 + 批量导入
- **结果导出**：判定矩阵 Excel、审查报告 Word、审计 JSON、修订稿 Word
- **数据看板**：任务量、判定分布、资源统计（手写 SVG 图表，无图表库依赖）

## 技术栈

### 后端

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 17 | 运行环境 |
| Spring Boot | 3.2.5 | Web 框架 |
| Spring Security | 随 parent | 认证与授权 |
| MyBatis-Plus | 3.5.5 | ORM 框架 |
| PostgreSQL + pgvector | 16 / 0.8.2 | 关系型数据库 + 向量检索（HNSW） |
| JWT (jjwt) | 0.12.5 | Token 认证 |
| Apache POI | 5.2.5 | Word 解析与生成（poi / poi-ooxml / poi-scratchpad） |
| FastJSON2 | 2.0.47 | JSON 处理 |
| json-schema-validator | 1.4.0 | 结构化输出校验 |
| WebSocket | 随 parent | 实时通信 |

### 前端

| 技术 | 版本 | 用途 |
|------|------|------|
| React | ^18.3 | UI 框架 |
| TypeScript | ^5.5 | 类型安全 |
| Vite | ^5.4 | 构建工具 |
| Ant Design | ^5.21 | UI 组件库 |
| Zustand | ^4.5 | 状态管理 |
| Axios | ^1.7 | HTTP 请求 |
| react-router-dom | ^6.26 | 路由 |
| react-virtuoso | ^4.18 | 虚拟滚动 |
| mammoth | ^1.8 | 浏览器端 Word 预览 |

> 前端当前未配置测试框架与 ESLint/Prettier；依赖均为 caret 范围而非 pin。后端有 12 个测试类，覆盖 `document`、`export`、`modelconfig`、`review.chunk`、`review.core`、`review.sar`、`rule.engine`、`scenario`。

### 基础设施

| 技术 | 用途 |
|------|------|
| Docker & Docker Compose | 容器化部署（db / backend / frontend 三服务） |
| Nginx | 前端静态资源服务与反向代理 |

## 项目结构

后端按**业务域**组织（而非 controller/service/entity 平铺），每个域自带 `controller` / `service` / `entity` / `repository` / `dto`。

```
ai-Document-Review/
├── backend/                                  # Spring Boot 后端
│   └── src/main/java/com/aireview/
│       ├── AiReviewApplication.java          # 启动入口
│       ├── auth/                             # 认证域
│       │   ├── controller/AuthController     # 注册、登录、刷新 Token、单位列表
│       │   ├── security/                     # SecurityConfig、JwtTokenProvider
│       │   │                                 # JwtAuthenticationFilter、FeatureAuthorizationFilter、SecurityUtils
│       │   └── service/AuthService
│       ├── common/                           # 跨域基础设施
│       │   ├── config/AsyncConfig            # 三级线程池定义
│       │   ├── dto/                          # ApiResponse、PageResponse
│       │   ├── health/HealthController       # /api/health
│       │   ├── persistence/                  # MyBatisPlusConfig、JSONB TypeHandler
│       │   │                                 # DocumentVectorSchemaMigration（向量列迁移）
│       │   ├── web/                          # GlobalExceptionHandler、WebConfig
│       │   └── websocket/                    # TaskProgressHandler、WebSocketService
│       ├── dashboard/                        # 统计看板域
│       ├── document/                         # 文档处理工具集
│       │   ├── WordParser                    # Word 解析，按一级标题切章
│       │   ├── ChunkUtils                    # 切片（CJK 感知 Token 估算）
│       │   ├── ChapterReferenceResolver      # 识别「见第X章 / 参见4.5条」并附带被引用章节
│       │   ├── DocumentSourceMapper          # 解析结果 → 结构化原文
│       │   ├── DocumentEvidenceLocator       # 证据定位
│       │   └── DocumentRevisionWriter        # 生成修订稿 Word
│       ├── export/ReviewExportUtil           # Excel / Word / 审计 JSON 导出
│       ├── modelconfig/                      # AI 模型配置域
│       │   └── service/                      # AiModelService、ReasoningModeAdapter、AiCallOptions
│       ├── review/                           # 审查域（三层）
│       │   ├── controller/UnifiedReviewController  # 跨管线合并查询
│       │   ├── core/                         # ReviewResultSchema（JSON Schema）、ReviewCategory
│       │   │                                 # DocumentRuleReviewSupport（文档级审查共用）、SourceEditStore
│       │   ├── llm/                          # JsonExtractor、ThinkingModeDetector
│       │   ├── chunk/                        # CHUNK 管线：controller / entity / repository / service
│       │   │   └── service/ReviewService     # 核心审查逻辑
│       │   └── sar/                          # SAR 管线（后端完整，前端入口已关闭）
│       │       └── service/SarReviewService
│       ├── rule/                             # 规则域
│       │   ├── engine/                       # RuleParser（四段式提示词）、RuleMetadata
│       │   │                                 # RuleDispatcher（按类型调度）、MultiRuleParser
│       │   ├── entity/                       # Rule / RuleCheck / RuleFolder / RuleLibrary + Sar* 镜像
│       │   └── service/                      # RuleService、RuleLibraryService、ChecklistRuleImportService
│       ├── scenario/                         # 场景域（CHUNK / SAR 对称）
│       └── user/                             # 用户与权限域
│           ├── controller/                   # UserController、MemberController、UserManagementController
│           ├── entity/                       # User、Unit、UserFeatureAssignment、UserRuleAssignment
│           └── service/                      # UserService、MemberService、FeaturePermissionService、IdCardSupport
│   └── src/main/resources/
│       ├── application.yml                   # 应用配置
│       └── schema.sql                        # 幂等建表 + 滚动迁移 + 种子数据
│
├── frontend/                                 # React 前端（feature-first）
│   └── src/
│       ├── main.tsx                          # 入口，挂载 App + antd ConfigProvider
│       ├── app/App.tsx                       # 唯一路由表
│       ├── features/
│       │   ├── auth/                         # LoginPage、authStore
│       │   ├── dashboard/                    # DashboardPage（工作台）、DataBoardPage（数据看板）
│       │   ├── review/
│       │   │   ├── api/                      # reviews / sarReviews / pipelineApi
│       │   │   ├── pages/ReviewWorkspacePage # 30 行壳层
│       │   │   ├── workspace/                # useReviewWorkspace（状态）+ components + helpers
│       │   │   ├── components/FileUploader
│       │   │   └── store/logStore            # WebSocket 日志
│       │   ├── rules/                        # RuleListPage（三级视图）、RuleUploader
│       │   ├── scenarios/                    # ScenarioListPage
│       │   ├── modelConfig/                  # ModelConfigPage
│       │   └── users/                        # MemberManagementPage、ProfilePage
│       └── shared/
│           ├── api/request.ts                # Axios 实例 + Token 拦截器
│           ├── components/                   # AppLayout、ProtectedRoute
│           ├── utils/                        # constants、websocket（TaskWebSocket 单例）
│           └── styles/
│
├── docker-compose.yml                        # 容器编排
├── prompts/ · output/ · docs/                # 提示词、导出产物、文档
└── SAR_PIPELINE.md                           # SAR 管线设计说明
```

## 快速开始

### 环境要求

Docker Desktop（Mac / Windows / Linux）。无需本地安装 Java、Node.js、PostgreSQL。

### 一键启动

```bash
git clone <repo-url>
cd ai-Document-Review
docker compose up -d --build
```

首次构建需下载 Maven 与 npm 依赖，耗时取决于网速；后续仅编译源码。

### 访问地址

| 服务 | 地址 | 容器 |
|------|------|------|
| 前端界面 | http://localhost:3030 | ai-review-frontend（内部 80） |
| 后端 API | http://localhost:8080 | ai-review-backend |
| 数据库 | localhost:5432 | ai-review-db |

### 默认管理员账号

| 项目 | 值 |
|------|-----|
| 账号 | admin_root |
| 密码 | admin_root |
| 角色 | supervisor（显示为「平台管理员」） |

### 子路径部署

```bash
APP_BASE=/office-app/ docker compose build frontend
```

Vite 通过 `BASE_URL` 自动处理资源前缀，默认根路径 `/`。

### 停止与重建

```bash
docker compose down -t 3          # 停止
docker compose down -v            # 停止并删除数据卷
docker compose up -d --build      # 重新构建
```

后端容器设置了 `SPRING_SQL_INIT_MODE=always`，每次启动都会重跑 `schema.sql`。该脚本完全幂等（`CREATE TABLE IF NOT EXISTS` / `ADD COLUMN IF NOT EXISTS` / `INSERT ... ON CONFLICT DO NOTHING`），因此**升级时无需删除数据卷**即可自动应用迁移。

## 数据库设计

PostgreSQL 16 + pgvector（镜像 `pgvector/pgvector:0.8.2-pg16`）。`schema.sql` 共建 23 张表，分为共享 / CHUNK / SAR 三组。

**共享表**

| 表名 | 说明 |
|------|------|
| users | 账号即成员（登录名、密码哈希、角色、所属单位、身份证号） |
| units | 单位组织树（`parent_id` 为空为一级单位） |
| user_feature_assignment | 用户可用功能码授权（统一权限模型，在 DO 块内创建） |
| ai_model_config | AI 模型配置（厂商、密钥、端点、参数、`model_type`=chat/embedding/reranker、`embedding_dimension`、推理模式开关） |

**CHUNK 管线表**

| 表名 | 说明 |
|------|------|
| rule_libraries | 规则库 |
| rule_folders | 规则库下的二级文件夹（按规则类型分组，可整组停用） |
| rules | 规则正文与元数据（`rule_code`、`rule_type`、`sections`、`keywords` 等） |
| rule_checks | 规则拆出的原子检查项 |
| scenarios | 审查场景 |
| scenario_library_mapping | 场景 ↔ 规则库关联 |
| user_library_assignment | 用户 ↔ 规则库授权 |
| review_tasks | 审查任务（UUID 主键、状态、`ai_result` JSONB、`problem_count`、`review_category`、`quality_check_enabled`） |
| review_audit_logs | 人工复核审计（动作、前后 JSON、操作者） |
| document_blocks | 文档切块与向量（原生 `embedding vector` 列 + HNSW 索引） |

**SAR 管线表（`sar_*` 前缀，10 张）**

与 CHUNK 侧结构对称：`sar_rule_libraries` / `sar_rule_folders` / `sar_rules` / `sar_rule_checks` / `sar_scenarios` / `sar_scenario_library_mapping` / `sar_user_library_assignment` / `sar_review_tasks` / `sar_review_audit_logs` / `sar_document_blocks`（含向量列 + HNSW 索引）。表结构与端点均已就位，前端入口当前关闭。

> `schema.sql` 末尾包含滚动迁移（`ADD COLUMN IF NOT EXISTS`、外键改 `ON DELETE SET NULL`、`R-BASIC-QUALITY` → `R-Q` 改名）以及 supervisor 账号与内置「基础文字质量审查」规则的幂等种子数据。脚本用 `separator: "^^^ END OF SCRIPT ^^^"` 让 PG JDBC 整体解析，避免 `DO` 块被分号切断。

## 核心工作流程

### CHUNK 管线：全文逐章审查

```
上传文档 -> 选择场景 + 模型 -> 提交任务
                                |
                    WordParser 按一级标题切分章节
                                |
                    ChunkUtils 按 Token 限制切片（上限 25600）
                    通用章节段合并为一片，试验项目章节一章一片
                                |
                    RuleDispatcher 为每个切片选出适用规则
                                |
                    逐章节并行审查（每章节 = 一次 AI 调用）
                    单元内容 = 命中规则 + 本章节切片 + 被引用章节切片
                                |
                    单章节失败只标记为可重试切片，不影响其他章节
                                |
                    document_specific 规则跑一次文档级综合审查
                                |
                    aggregateResults 跨切片 fingerprint 去重 + 枚举归一化
                                |
                    汇总结果 -> 审查完成
```

### 管线细节

1. **每章节单元**：system prompt = `RuleDispatcher` 为该章节命中的规则（四段式结构化提示词）；user message = 本章节正文 + 通过 `ChapterReferenceResolver` 识别到的被引用章节。被引用内容仅作上下文，不在其上套用本次规则。

2. **通用章节段合并**：`review.chunk.general-section-end-chapter=0` 时自动识别——取「第一个试验项目章节」之前的全部章节（含封面、目录等前置内容）合并为一个切片，让签署完整性、目录与正文一致性、术语一致性这类跨章检查能在同一次调用内完成。仅当试验概述写法不规范、提不出试验项目清单导致识别失败时，才需手动指定章节号。

3. **并发调度**：`Semaphore(review.parallel.chunk-concurrency)` 控制单任务的章节并发（默认 6），提交到独立的 `chunkReviewExecutor` 执行。`review.parallel.global-ai-concurrency` 默认 **0（关闭）**，各任务并发相互独立——设为正数会变成跨任务共享的总闸门，导致「另一个文档一审查，我这个就明显变慢」，仅在上游按账号而非按任务限流时才需开启。

4. **单章节失败隔离**：某章节 AI 调用失败（如 429）只把该切片标记为 `failed/retryable` 写入 `failedChunks`，不触发拆批重发放大。任务照常完成，用户可在结果页「重审失败切片」单独补审。

5. **收敛参数**：`temperature=0`、`top_p=1`、`max_tokens` 按 `4096+检查项数×512` 动态计算（下限 8192、初始上限 24576，截断时按第 8 条升档）、`seed=sha1(taskId+chunkIdx+0)` 取前 8 字节，保证同任务可复现、跨模型可对比。

6. **结构化输出**：OpenAI 兼容协议走 `response_format=json_schema`，Anthropic 走 `tool_use + tool_choice`，强制模型在解码阶段生成合法 JSON，由 `ReviewResultSchema` 定义、`json-schema-validator` 校验。

7. **文档级综合审查**：所有章节审完后，若场景含 `rule_type=document_specific` 规则，用「章节目录 + 各章节摘要」跑一次文档级调用。该侧预算独立（`review.document.rule-budget-tokens=4000`），**超预算是拆批而非丢规则**；证据包上限 `max-evidence-chars=55000`（实测 37 章大纲需 48300 字符才能全覆盖）。

8. **JSON 截断升档**：确认因长度截断时，输出预算按 `24576 → 32768 → 65536` 升档重试（绝对上限 128000，防止 API 传入异常大值），最多 3 次整体尝试（`JSON_PARSE_MAX_ATTEMPTS`），每次换种子重新调用。

9. **业务规则覆盖约束**：system prompt 会计算本章注入的业务规则/原子检查项数量，要求 `check_results` 至少覆盖这些待判定项。无原子检查项的 Markdown 规则使用 `规则编号-C001` 作为默认检查项编号；模型漏返时后端补一条 Review 供矩阵复核。

10. **结果落盘**：汇总结果除写库外，还会导出一份 JSON 到容器内 `/app/output/审查结果_<文件名>_<模型>_<运行时间戳>.json`（路径在 `saveAiResultToFile` 中硬编码，docker-compose 通过 `./output:/app/output` 映射到宿主机 `output/`）。文件名中的不安全字符会被替换为下划线。写失败只记 `warn` 日志，不影响审查任务本身。

11. **全文质量检查开关**：内置文字质量规则 `R-Q` 默认注入每章，内置默认 7 项（C001 错别字、C002 语句通顺、C003 本章内术语一致、C004~C007 图号表号的唯一性/引用真实性/顺序/被引用完整性）。这 7 项是 `defaultBasicQualityChecks()` 的**兜底值**：实际使用的检查项从数据库规则 `R-Q` 的 `rule_checks` 加载，可在界面上增删改，仅当该行缺失或无启用检查项时才回退到内置默认。无问题时不进入矩阵，仅 Fail/Review 显示。新建任务关闭该开关后，未命中任何业务规则的章节标记为 skipped，不再调用模型。

### 规则调度矩阵

`RuleMetadata` 定义 6 种 `rule_type`：

| rule_type | 实际应用范围 |
|---|---|
| `global` | 注入每个章节切片 |
| `output` | 注入每个章节切片（与 global 同等对待） |
| `general_chapter` | 仅注入合并后的「通用章节段」切片 |
| `test_item_chapter` | 不按关键词触发；系统先从「试验概述/试验项目概述」提取声明的试验项目清单，再注入被识别为试验项目的一级章节 |
| `section_specific` | 只匹配一级章节标题；标题命中 `sections` 或 `keywords` 任一项才注入，不搜索正文或二三级标题 |
| `document_specific` | 不参与逐章调用；所有章节完成后单独跑文档级综合审查 |

无元数据的规则按 `global` 处理，保证旧规则库继续可用。规则列表页的「适用范围」展示的是 `keywords` 元数据——对 `section_specific` 它就是一级标题匹配关键词，对 `test_item_chapter` / `global` 更多是主题标签，真实范围以 `rule_type` 为准。

> `review.dispatch.basic-only-max-chapter` **已废弃并默认关闭（0）**。该档位在 `dispatchForChunk` 最开头就 return，会绕过全部规则元数据——配给低序号章节的专项规则不管配得多准都不会生效，日志里只留一条 `basic_chapter_profile`，很难察觉。其职能已被「通用章节段合并 + `general_chapter` 规则类型」取代。

### 角色权限

内部角色名保持 `supervisor` / `admin` / `user` 以兼容 JWT 与存量数据，显示语义如下：

| 功能 | user（用户） | admin（单位管理员） | supervisor（平台管理员） | 实现位置 |
|------|---------|--------|---------|---------|
| 提交审查 / 查看自己的任务 | Y | Y | Y | 按 `userId` 归属过滤 |
| 管理规则与规则库 | - | Y | Y | Controller 方法级 `@PreAuthorize("hasAnyRole('SUPERVISOR','ADMIN')")` |
| 管理审查场景 | Y | Y | Y | **无角色限制**，按创建者 `userId` 归属隔离 |
| 模型配置 | Y | Y | Y | **无角色限制**，任何已登录用户可增删改 |
| 数据看板 | - | Y | Y | `SecurityConfig`：`/admin/dashboard/**` 需 ADMIN/SUPERVISOR |
| 成员与权限 | - | Y | Y | `SecurityConfig`：`/admin/members/**` 需 ADMIN/SUPERVISOR，单位范围由 `MemberService` 逐次判定 |
| 用户角色与规则库授权 | - | - | Y | `SecurityConfig`：`/admin/**` 兜底需 SUPERVISOR |

`SecurityConfig` 中 `/api/v1/admin/members/**` 与 `/api/v1/admin/dashboard/**` 两条规则必须排在 `/api/v1/admin/**` 之前，否则会被后者先匹配掉。URL 层只放行到「是不是管理员」，「能不能管这个单位」由 `MemberService` / `DashboardStatsService` 按操作者的 `unit_id` 逐次判定。

> **注意**：场景管理与模型配置当前没有角色校验（无 `@PreAuthorize`、`SecurityConfig` 中也未匹配），任何已登录用户都能调用这些接口的写操作。前端仅通过菜单可见性区分，属于待收口的权限缺口。

审查入口另受**功能码**控制：`FeaturePermissionService.ENV_TEST_OUTLINE_REVIEW`（对应 `ReviewCategory.ENV_TEST_OUTLINE`）。用户需被授予该功能码或具备 supervisor 角色，才能看到「环境试验大纲审查」菜单组。后端由 `FeatureAuthorizationFilter` 拦截。

> 前端 `/analytics`（数据看板）路由本身未包 `ManagerProtectedRoute`，仅菜单项按 `isManager` 隐藏——非管理员直接输入 URL 仍可进入页面，实际数据拦截依赖后端。

## API 接口

所有接口以 `/api/v1` 为前缀（健康检查除外），返回统一格式：

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {},
  "timestamp": "2024-01-01T00:00:00"
}
```

### 认证 `/api/v1/auth`（无需登录）

`GET /units` 单位列表 · `POST /register` 注册 · `POST /login` 登录 · `POST /refresh` 刷新 Token

### CHUNK 审查 `/api/v1/reviews`

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/execute` | 提交审查任务 |
| GET | `/tasks` | 任务列表 |
| GET | `/tasks/{taskId}` | 任务详情 |
| DELETE | `/tasks/{taskId}` | 删除任务 |
| POST | `/tasks/{taskId}/re-review` | 整体重审 |
| POST | `/tasks/{taskId}/retry-failed-chunks` | 重审失败切片 |
| POST | `/tasks/{taskId}/cancel` | 取消任务 |
| GET | `/stats` | 个人统计 |
| PUT | `/tasks/{taskId}/check-decisions` | 人工复核判定 |
| GET | `/tasks/{taskId}/audit` | 审计日志 |
| GET | `/tasks/{taskId}/export` | 导出判定矩阵 Excel |
| GET | `/tasks/{taskId}/audit/export` | 导出审计 JSON |
| GET | `/tasks/{taskId}/report` | 导出审查报告 Word |
| PUT / DELETE | `/tasks/{taskId}/source-edits` | 保存 / 清除原文修订 |
| GET | `/tasks/{taskId}/revised-document` | 下载修订稿 Word |

### 跨管线查询 `/api/v1/reviews`（UnifiedReviewController）

与上表同前缀但子路径不冲突：`GET /all` 合并任务列表 · `GET /by-id/{taskId}` 按 ID 查详情 · `GET /by-id/{taskId}/sources` 结构化原文 · `GET /stats/all` 合并统计

### SAR 审查 `/api/v1/sar/reviews`

端点与 CHUNK 侧**完全对称**（同一组 `/execute`、`/tasks/**`、导出与修订接口）。后端可用，前端入口当前关闭。

### 规则 `/api/v1/rules`（SAR 侧为 `/api/v1/sar/rules`）

`POST /upload` 上传（增量 upsert） · `GET /upload-conflicts` 冲突查询（保留兼容） · `GET /{id}` 详情 · `DELETE /{id}` 删除 · `PUT /{id}/metadata` 编辑元信息 · `PUT /{id}/content` 编辑正文

> `POST /import-checklist`（Excel 检查单导入）**仅 SAR 侧提供**，CHUNK 侧 `RuleController` 无此端点。

### 规则库 `/api/v1/rule-libraries`（SAR 侧为 `/api/v1/sar/rule-libraries`）

规则库 CRUD + `GET /all` · 文件夹：`GET|POST /{libraryId}/folders`、`PUT|DELETE /folders/{folderId}`

### 场景 `/api/v1/scenarios`（SAR 侧为 `/api/v1/sar/scenarios`）

场景 CRUD（列表、`GET|PUT|DELETE /{id}`）

### 模型 `/api/v1/models`

模型 CRUD · `GET /enabled?modelType=` 按类型查启用 · `POST /test-connection` 连通性测试 · `GET /suggest-thinking-mode` 思考模式建议 · `PUT /{id}/toggle` 启停

### 用户与成员

| 前缀 | 端点 |
|---|---|
| `/api/v1/user` | `GET /me` 个人信息 · `PUT /password` 改密 |
| `/api/v1/admin/users` | 用户列表 · `PUT /{id}/role` 改角色 · `GET|POST /{id}/libraries` 规则库授权 · `DELETE /{id}` 删除 |
| `/api/v1/admin/members` | `GET|POST /units`、`DELETE /units/{unitId}` 组织树 · `POST /platform-accounts` 平台账号 · `GET /features` 功能码 · `GET|PUT /{memberId}/permissions` 权限 · `PUT /{memberId}/role` · `POST /{memberId}/reset-password` · `DELETE /{memberId}` · `POST /import` 批量导入 · `GET /import-template-headers` 导入模板表头 |

### 其他

`GET /api/v1/admin/dashboard` 系统统计 · `GET /api/health` 容器健康检查

## 配置说明

### 关键配置项（application.yml）

| 配置 | 默认值 | 说明 |
|------|--------|------|
| server.port | 8080 | 后端端口 |
| spring.datasource.hikari.maximum-pool-size | 20 | 数据库连接池 |
| spring.servlet.multipart.max-file-size | 200MB | 最大上传文件（请求上限 210MB） |
| spring.sql.init.mode | always | 每次启动重跑幂等 schema.sql |
| jwt.access-token-expiration | 3600000 | Access Token 有效期（1 小时） |
| jwt.refresh-token-expiration | 604800000 | Refresh Token 有效期（7 天） |
| review.retry.max-attempts | 4 | 4 次尝试 = 3 次重试；只对 IOException / 5xx / 429 重试，4xx 立即失败 |
| review.retry.interval-ms | 1000 | 指数退避 1s→2s→4s（总等待 7s） |
| review.chunk.max-tokens | 25600 | 每个切片最大 Token（章节超长按段落再分） |
| review.chunk.overlap-tokens | 0 | 切片重叠 token |
| review.chunk.general-section-end-chapter | 0 | 通用章节段结束章节号；0 = 自动识别 |
| review.chunk.rule-budget-tokens | 16000 | 逐章「规则清单」段 token 上限；**超限规则被静默跳过**，宁可调大 |
| review.document.rule-budget-tokens | 4000 | 文档级单批规则预算；超限拆批，一条不丢 |
| review.document.max-evidence-chars | 55000 | 文档级证据包字符上限（上限而非固定开销） |
| review.parallel.chunk-concurrency | 6 | 单任务章节并发；`chunkReviewExecutor` 池大小自动跟随 |
| review.parallel.global-ai-concurrency | 0 | 跨任务总闸门；0 = 关闭，任务间并发互不影响 |
| review.dispatch.basic-only-max-chapter | 0 | **已废弃**，非 0 会绕过全部规则元数据 |
| review.rag.* | 见 yml | 保留的 RAG 调参组；**`RagReviewService` 在代码中不存在**，该组配置当前无对应实现 |
| review.sar.* | 见下 | SAR 管线参数 |
| async.core-pool-size / max-pool-size / queue-capacity | 4 / 8 / 100 | 任务级线程池 `reviewTaskExecutor` |
| logging.level.com.aireview | DEBUG | 业务日志级别 |

> `jwt.secret` 与数据库密码当前硬编码在 `application.yml` 和 `docker-compose.yml` 中，生产部署应通过环境变量外置。

### SAR 管线参数（`review.sar.*`）

`check-concurrency=4` · `recall-top-k=30` · `evidence-max-blocks=10` · `region-max-blocks=14` · `route-confidence-threshold=0.45` · `max-checks-per-call=8` · `max-evidence-per-call=16` · `block-max-chars=1800` · `embedding-batch-size=24` · `verify.enabled=false` / `verify.adaptive=true` · `consistency.enabled=true`（`max-input-chars=48000`、`per-chapter-max-chars=4800`、`windows-per-chapter=4`） · `quality.full-scan`（`batch-blocks=4`、`concurrency=2`、`failure-threshold=4`） · `quality.structure-index.enabled=true` · `quality.terminology.enabled=true`（`max-observations=160`） · `vector-index.hnsw-ef-search=100`。详见 [SAR_PIPELINE.md](SAR_PIPELINE.md)。

### 线程池架构（三级隔离避免死锁）

1. **reviewTaskExecutor**（任务级）：核心 4、最大 8、队列 100，前缀 `review-task-`。用于 `@Async` 任务启动，一个审查任务 = 一次 async 调用。
2. **chunkReviewExecutor**（切片级）：池大小 = `review.parallel.chunk-concurrency × async.max-pool-size`，**自动跟随配置**，无需手动改 `AsyncConfig`。用于 CHUNK 单章节并行审查。
3. **sarCheckExecutor**（检查项级）：用于 SAR 检查项并行评估，并发由 `review.sar.check-concurrency` 控制。

**设计原则**：任务级、切片级、检查项级三层隔离，避免大任务占满线程池导致小任务饿死。

> **收敛常量**（不开放配置，集中在 `ReviewService` 顶部）：`temperature=0`、`top_p=1`、输出预算按 `4096+检查项数×512` 动态计算（下限 8192、初始上限 24576，确认长度截断后升档至 32768 / 65536，绝对上限 128000）。每章节单次调用，不再双采样。这些值是「跨模型可比」的契约本身，因此不作为普通审查参数开放。

## 审查结果字段说明（`review_tasks.ai_result`）

判定枚举只有三级 **Pass / Fail / Review**（旧的 Partial / N/A 已并入 Review），**不再有 severity**。

顶层字段：

| 字段 | 含义 |
|---|---|
| `totalChunks` / `chunkResults` | 切片总数与逐切片结果 |
| `overallScore` | 平均分（模型未返回 `overall_score` 时不输出） |
| `totalIssues` / `allIssues` | 跨切片 fingerprint 去重后的问题总数与扁平列表（旧问题视图） |
| `allCheckResults` / `totalCheckResults` / `checkStatusCounts` | 检查项判定矩阵（当前主视图）与按 `status` 的计数 |
| `categoryCounts` | 按 `category`（格式/完整性/标准符合性/逻辑一致性/术语一致性/其他）分桶 |
| `confidenceCounts` | 按 `confidence`（high/medium/low/needs_review）分桶 |
| `passedRuleCoverage` | 各 `rule_code` 在 `passed_items` 中的命中次数 |
| `failedChunks` / `failedChunkCount` | 因 AI 调用失败保留的切片，可在 UI 重审 |
| `originalSources` / `sourceTextMode` | 重建的原文章节，供前端右侧定位/高亮 |
| `modelName` / `modelKey` | 任务使用的模型，用于跨模型对比归类 |
| `crossModelEligible` | `false` 表示思维模型，不应参与跨模型对比 |
| `samplingStrategy` | 顶层固定为 `single`；切片级在双采样路径下可能为 `double`（当前默认单采样） |

`allIssues[i]` 字段：

| 字段 | 含义 |
|---|---|
| `location` / `description` / `suggestion` / `rule` / `rule_code` / `evidence` | 与 prompt schema 一致；manifest 之外的非法 `rule_code` 会被丢弃 |
| `category` | 强制映射到枚举；无法判断 → `其他`（无 severity 字段） |
| `sourceChunk` / `sourceTitle` / `sourceRefs` | 关联的切片号、章节标题与原文引用，用于前端定位 |
| `fingerprint` | `sha1(归一化location + "\|" + rule_code)`，跨切片去重主键 |
| `confidence` | 默认 `single`；跨章节重复命中同一 fingerprint 时在 `aggregateResults` 中升为 `high` |
| `occurrences` | 跨切片去重后的命中次数 |

> SAR 管线的 `ai_result` 结构不同（`allCheckResults` 一违规一行、`retrievalStats` 含 `regionMaxBlocks`/`verifyAdaptive`/`consistencyFindings`，并可能有 `check_code=CONSISTENCY` 的跨章一致性行），详见 [SAR_PIPELINE.md](SAR_PIPELINE.md)。

## 前端架构

### 路由表（`app/App.tsx`）

外层 `/` 包 `ProtectedRoute` + `AppLayout`：

| path | 组件 / 行为 | 守卫 |
|---|---|---|
| `/login` | LoginPage | 无 |
| `/`（index） | → `/dashboard` | — |
| `dashboard` | DashboardPage | — |
| `chunk/scenarios` | ScenarioListPage（`reviewMode="CHUNK"`） | FeatureProtectedRoute |
| `chunk/rules` | RuleListPage（`reviewMode="CHUNK"`） | FeatureProtectedRoute |
| `review/:taskId` | ReviewWorkspacePage | FeatureProtectedRoute |
| `models` | ModelConfigPage | — |
| `analytics` | DataBoardPage | 仅菜单隐藏，无路由守卫 |
| `profile` | ProfilePage | — |
| `members` | MemberManagementPage | ManagerProtectedRoute |
| `sar/scenarios` · `sar/rules` | → `/chunk/*` | 重定向 |
| `scenarios` · `rules` | → `/chunk/*` | 重定向 |
| `review` · `users` · `*` | → `/dashboard` · `/members` · `/dashboard` | 重定向 |

### 侧边栏菜单（`AppLayout`）

- 工作台 → `/dashboard`
- 环境试验大纲审查（需 `ENV_TEST_OUTLINE_REVIEW` 功能码或 supervisor）
  - 全文逐章审查
    - 审查场景 → `/chunk/scenarios`
    - 审查规则 → `/chunk/rules`
- 模型管理 → `/models`
- 数据看板 → `/analytics`（仅管理员可见）
- 成员与权限 → `/members`（仅管理员可见）

右上角用户下拉：个人信息 → `/profile`、退出登录。角色标签：supervisor = 平台管理员（红）、admin = 单位管理员（蓝）、user = 用户。

布局层挂了两个全局副作用：`*` 通道的 WebSocket 日志订阅（离开工作区仍持续累积），以及路由变化时 `Modal.destroyAll()` + 清理 body scroll lock。

### 页面职责

| 组件 | 职责 |
|---|---|
| LoginPage | 登录/注册，含单位选择 |
| DashboardPage | 工作台：任务列表、新建审查弹窗、WebSocket 进度 |
| DataBoardPage | 数据看板，手写 SVG 图表（Donut / BarList / LineTrend / ResourceStat） |
| RuleListPage | 规则库 / 文件夹 / 规则三级管理，接收 `reviewMode` prop |
| ScenarioListPage | 场景 CRUD，接收 `reviewMode` prop |
| ReviewWorkspacePage | 审查工作区壳层（30 行），逻辑委托给 `workspace/` |
| ModelConfigPage | 模型 CRUD、连通性测试、thinking mode 建议 |
| MemberManagementPage | 组织树、成员、功能授权、批量导入 |
| ProfilePage | 个人信息与改密 |

`features/review/workspace/` 是唯一拆成 hook + 展示层的模块：`useReviewWorkspace.ts` 承载状态逻辑，`components.tsx` 与 `helpers.tsx` 负责渲染。

### API 层

`shared/api/request.ts` 提供 Axios 实例、`ApiResponse` 类型与 Token 拦截器。各 feature 自带 api 目录，CHUNK / SAR 双份（`rules.ts` / `sarRules.ts` 等），由 `features/review/api/pipelineApi.ts` 统一派发：`getScenarioApi/getRuleApi/getReviewApi(mode)` 按 CHUNK/SAR 返回同形状 client，另有 `getUnifiedReviewList/Stats` 与跨管线详情探测。

> SAR 侧 API 文件完整存在，但由于路由已重定向、没有页面传入 `"SAR"`，`sarRules` / `sarScenarios` / `sarReviews` 目前仅通过 `pipelineApi` 的 mode 分支可达，实际处于未启用状态。

### 技术特性

- **虚拟滚动**：检查项列表用 react-virtuoso，千级列表流畅交互
- **状态管理**：Zustand（`authStore` 认证、`logStore` WebSocket 日志），无 react-query 一类数据层
- **实时通信**：WebSocket 订阅任务进度与日志，跨页面持久化到 `logStore`
- **Token 刷新**：全局单例 Promise，避免并发 401 时重复刷新
- **懒加载**：详情页按需加载源文件，大任务秒开
- **子路径部署**：通过 Vite `BASE_URL` 支持挂载到子路径

## 性能优化

**后端**

- `review_tasks.problem_count` 缓存问题总数，列表查询无需反序列化大型 `ai_result` JSON
- 源文件按需加载，详情接口不默认返回结构化原文
- 三级线程池隔离，大任务不阻塞小任务
- SAR 嵌入批大小 24 条/批，减少网络往返
- pgvector HNSW 索引加速检索（`ef_search=100`）
- CHUNK 每章节单次调用，规避双采样 + 批处理导致的 429 限流风暴
- 单章节失败只标记 retryable，可在 UI 单独补审

**前端**

- 检查项列表虚拟滚动
- WebSocket 进度内存缓存（`ConcurrentHashMap`），页面刷新后立即恢复
- Token 刷新单例 Promise，N 个 401 请求共享一次刷新
- 结构化原文按需加载
- 表格列宽响应式适配（ResizeObserver）

## 开发指南

### 新增规则类型

1. 在 `rule/engine/RuleMetadata` 中添加新的 `TYPE_*` 常量
2. 在 `rule/engine/RuleDispatcher.dispatchForChunk()` 中实现调度逻辑
3. 更新 `review/core/ReviewResultSchema` 中的规则清单构建逻辑
4. 前端 `RuleListPage` 中添加类型选项

### 新增 AI 模型厂商

1. 在 `modelconfig/entity/AiModelConfig` 的 `provider` 字段添加新值
2. 在 `modelconfig/service/AiModelService` 中添加厂商特定的端点解析与请求构造逻辑
3. 如涉及推理/思考模式，在 `ReasoningModeAdapter` 中补充适配
4. 前端 `ModelConfigPage` 的供应商下拉中添加选项

### 重新启用 SAR 前端入口

后端与 API 层完整可用，只需前端改动：

1. `app/App.tsx`：把 `sar/scenarios`、`sar/rules` 的 `Navigate` 重定向替换为 `ScenarioListPage` / `RuleListPage` 并传入 `reviewMode="SAR"`（两个页面已支持该 prop，`chunk/*` 路由就是这么传的）
2. `shared/components/AppLayout`：菜单是三级结构（业务域 → 管线 → 叶子），`chunk-section` 同级现留有一个空位，在此补回「结构化精准审查」分组；同时要扩展下方按 `/chunk/*` 前缀计算 `selectedKey` 的逻辑，否则 SAR 页面高亮不到菜单项
3. `DashboardPage`：改动量最大。`'CHUNK'` 在此页多处硬编码——提交任务时的 `mode: 'CHUNK'`、`getScenarioApi('CHUNK')`、`getReviewApi('CHUNK')`、成功提示里的 `PIPELINE_LABEL.CHUNK`，以及统计只取 `s.byMode.CHUNK`。需要在新建审查弹窗加入管线选择并把选中值贯穿这些调用点。任务列表侧无需改动：`apiForTask` 已按 `task.reviewMode` 分流

### 新增审查管线

1. **数据库**：在 `schema.sql` 中创建 `xyz_*` 表（参考 `sar_*` 表结构）
2. **后端**：新建 `review/xyz/` 包，实现 `entity` / `repository` / `service/XyzReviewService` / `controller/XyzReviewController`（`/api/v1/xyz/reviews`）
3. **规则与场景**：在 `rule` / `scenario` 域中添加 `Xyz*` 镜像实体、Mapper、Service、Controller
4. **前端**：新增 `xyzReviews.ts` / `xyzRules.ts` / `xyzScenarios.ts`，在 `pipelineApi.ts` 中添加 mode 分支
5. **路由与菜单**：在 `App.tsx` 添加 `/xyz/*` 路由，在 `AppLayout` 添加侧边栏入口
6. **跨管线查询**：在 `UnifiedReviewController` 中纳入新管线的任务与统计

### 规则编写规范

**Markdown 多规则模板**（推荐）：

```markdown
## 1. 霉菌试验-设备名称与件号

- 规则编号：13-02-deviceIdentification
- 规则类型：section_specific
- 检查项：设备名称、件号
- 关键词：霉菌

### 审查内容

霉菌试验章节：核查“设备名称与件号”是否满足检查单确认目标。

### 审查步骤

1. 核查供应商件号必填合规性
2. ...
```

**Rule JSON**（用于原子检查项）：

```json
{
  "version": "1.0",
  "rules": [
    {
      "rule_code": "DO160G-5-QTP",
      "name": "DO160G 第5章 QTP评估检查",
      "rule_type": "section_specific",
      "applies_to": {
        "sections": ["5"],
        "keywords": ["QTP", "DO160G"]
      },
      "checks": [
        {
          "check_code": "DO160G-5-QTP-001",
          "check_type": "presence",
          "question": "是否满足检查项要求？",
          "pass_criteria": "QTP中应提供能够证明该检查项满足要求的明确内容。",
          "category": "标准符合性",
          "evidence_required": true
        }
      ]
    }
  ]
}
```

## 常见问题排查

**审查任务一直 PROCESSING**
- 检查 WebSocket 连接是否正常（浏览器控制台）
- 检查后端日志是否有异常堆栈
- 检查线程池是否耗尽（`review.parallel.chunk-concurrency` 是否过大）

**模型调用频繁 429**
- 降低 `review.parallel.chunk-concurrency`
- 若上游按账号限流且多任务并行，可把 `review.parallel.global-ai-concurrency` 从 0 改为正数作为总闸门
- 检查模型配额与 `review.retry.max-attempts` 配置

**配好的专项规则不生效**
- 确认规则 `is_valid` 为 true、所在文件夹已启用（`rule_folders.enabled`）
- 确认场景关联了该规则库（`scenario_library_mapping`）
- `section_specific` 只匹配**一级标题**，不搜正文与二三级标题——检查 `sections` / `keywords` 是否命中 H1
- 确认 `review.dispatch.basic-only-max-chapter` 为 0；非 0 会让低序号章节绕过全部规则元数据
- 规则清单超过 `review.chunk.rule-budget-tokens` 会被**静默跳过**，只留一行日志，需调大该值

**前端 401 循环**
- 检查 refresh token 是否过期（超过 7 天未活跃）
- 检查 localStorage 中的 token 与 refreshToken
- 清除浏览器缓存并重新登录
