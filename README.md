# AI 智能文件审查系统

基于多种大语言模型的智能文档审查平台，支持 Word 文档的自动化审查、问题检测与改进建议。系统提供规则管理、场景配置、多模型接入和角色权限控制等功能。

## 功能概览

- **文档智能审查**：上传 Word 文档（.doc/.docx），AI 按章节逐段审查并生成审查报告
- **规则库管理**：上传 Markdown 或 JSON 格式规则文件，按规则库分类管理
- **审查场景配置**：将多个规则库组合为审查场景，针对不同业务场景灵活配置
- **多模型支持**：接入 OpenAI、Anthropic、Moonshot、百度、阿里、讯飞等主流 AI 厂商，支持自定义厂商
- **实时进度追踪**：WebSocket 实时推送审查进度、日志与结果
- **角色权限控制**：项目主管 / 管理员 / 普通用户三级权限体系
- **用户管理**：用户注册、登录、密码修改、规则库分配

## 技术栈

### 后端

| 技术 | 版本 | 用途 |
|------|------|------|
| Java | 17 | 运行环境 |
| Spring Boot | 3.2.5 | Web 框架 |
| Spring Security | - | 认证与授权 |
| MyBatis-Plus | 3.5.5 | ORM 框架 |
| PostgreSQL | 16 | 关系型数据库 |
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
│   │   ├── config/                   # 配置类（Security, JWT, WebSocket, MyBatis）
│   │   ├── controller/               # REST 控制器（9个）
│   │   │   ├── AuthController        # 认证：注册、登录、刷新 Token
│   │   │   ├── ReviewController      # 审查：提交、查询、取消、重审、删除
│   │   │   ├── ScenarioController    # 场景：增删改查
│   │   │   ├── RuleController        # 规则：上传、查询、删除
│   │   │   ├── RuleLibraryController # 规则库：增删改查
│   │   │   ├── AiModelConfigController # 模型配置：增删改查、启停
│   │   │   ├── UserManagementController # 用户管理（主管权限）
│   │   │   ├── UserController        # 个人信息、修改密码
│   │   │   └── HealthController      # 健康检查
│   │   ├── entity/                   # 数据实体（8个）
│   │   ├── dto/                      # 数据传输对象（15个）
│   │   ├── repository/               # MyBatis Mapper 接口
│   │   ├── service/                  # 业务逻辑层（8个）
│   │   ├── util/                     # 工具类
│   │   │   ├── WordParser            # Word 文档解析（按一级标题切分章节）
│   │   │   ├── ChunkUtils            # 文档切片（CJK 感知 Token 估算）
│   │   │   ├── RuleParser            # 规则文件解析与提示词构建
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
│   │   │   ├── DashboardPage         # 工作台（统计、任务列表、新建审查）
│   │   │   ├── ReviewWorkspacePage   # 审查详情（进度、日志、结果）
│   │   │   ├── RuleScenarioPage      # 规则与场景管理（合并页面）
│   │   │   ├── ModelConfigPage       # 模型配置管理
│   │   │   ├── UserManagementPage    # 用户管理（主管权限）
│   │   │   └── ProfilePage           # 个人中心
│   │   ├── components/               # 公共组件
│   │   │   ├── AppLayout             # 侧边栏布局
│   │   │   ├── FileUploader          # 文件上传
│   │   │   ├── ProtectedRoute        # 路由鉴权
│   │   │   └── ReviewResultCard      # 审查结果卡片
│   │   ├── api/                      # API 接口封装
│   │   ├── store/                    # Zustand 状态管理
│   │   └── utils/                    # 工具（常量、WebSocket）
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

系统使用 PostgreSQL 16，包含 9 张核心表：

| 表名 | 说明 |
|------|------|
| users | 用户表（邮箱、密码哈希、角色） |
| rule_libraries | 规则库（名称、描述、创建者） |
| rules | 审查规则（名称、类型、内容、所属规则库） |
| scenarios | 审查场景（名称、描述） |
| scenario_library_mapping | 场景-规则库关联 |
| scenario_rule_mapping | 场景-规则关联 |
| review_tasks | 审查任务（UUID主键、状态、AI结果JSONB） |
| ai_model_config | AI模型配置（厂商、密钥、端点、参数） |
| user_library_assignment | 用户-规则库权限分配 |

## 核心工作流程

### 文档审查流程

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

### 审查管线：逐章节单次调用

审查以**章节切片**为最小单元，每个章节切片对应**一次独立 AI 调用**，调用量与章节数严格 1:1：

1. **每章节单元**：system prompt = `RuleDispatcher` 为该章节命中的规则（四段式结构化提示词）；user message = 本章节切片正文 + 该章节通过 `ChapterReferenceResolver` 识别到的、被引用的其他章节切片（“见第X章 / 参见 4.5 条 / 参考<标题>”等）。被引用章节内容仅作上下文，不在其上套用本次规则。
2. **并发调度**：父线程 `Semaphore(review.parallel.chunk-concurrency)` 控制单任务的章节并发，提交到独立的 `chunkReviewExecutor` 线程池执行；各章节互不影响。
3. **单章节失败隔离**：某章节 AI 调用失败（如 429）只把该切片标记为 `failed/retryable`，写入 `failedChunks`，不触发"拆批重发"之类的放大重试；任务照常完成，用户可在结果页"重审失败切片"单独补审。
4. **收敛参数**：`temperature=0`、`top_p=1`、`max_tokens=8192`、`seed=sha1(taskId+chunkIdx+0)` 取前 8 字节，保证同任务可复现、跨模型可对比。
5. **结构化输出**：OpenAI 兼容协议走 `response_format=json_schema`，Anthropic 走 `tool_use+tool_choice`，强制模型在解码阶段就生成合法 JSON。
6. **文档级综合审查**：所有章节审完后，若场景含 `rule_type=document_specific` 规则，再用"章节目录 + 各章节摘要"跑一次文档级综合调用。

> **历史说明**：旧版曾用 `ChunkBatchPlanner`（按规则签名 + token 预算把多切片打包成批）+ 非思维模型双采样 + 校准波动态调整 + 批失败拆回单切片重发的"批处理 + 采样收敛"方案。该方案在模型提供方限流（HTTP 429）时，会因"双采样翻倍调用 + 批失败瞬间拆成大量单切片重发"形成"越限流越猛打"的失败风暴，导致大量切片审查失败。现已改为上述逐章节单次调用方案；`ChunkBatchPlanner`、`ModelTier`、`RuleParser.buildBatchStructuredSystemPrompt`、`ReviewResultSchema.batchSchema()` 等批处理类已不再被审查管线调用（暂保留代码，无业务影响）。

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
| 认证 | /api/v1/auth/** | 注册、登录、刷新Token（无需认证） |
| 审查 | /api/v1/reviews/** | 提交审查、任务管理、统计 |
| 场景 | /api/v1/scenarios/** | 场景增删改查 |
| 规则 | /api/v1/rules/** | 规则上传、查询、删除 |
| 规则库 | /api/v1/rule-libraries/** | 规则库管理 |
| 模型 | /api/v1/models/** | AI模型配置管理 |
| 用户管理 | /api/v1/admin/users/** | 用户管理（主管权限） |
| 个人 | /api/v1/user/** | 个人信息、改密 |
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
| review.parallel.chunk-concurrency | 6 | 单任务并行审查的章节切片数 |
| review.retry.max-attempts | 4 | AI 调用失败重试次数（4xx 立即失败；5xx 指数退避；429 额外尊重 `Retry-After` 头并采用更陡退避，封顶 60s） |
| review.retry.interval-ms | 1000 | 重试初始间隔（5xx 退避 1s→2s→4s；429 退避 2s→4s→8s 并不低于 `Retry-After`） |
| review.prompts.path | ./prompts/prompts/prompts.json | v2 提示词迁移源文件 |
| async.core-pool-size | 4 | 异步线程池核心线程数（任务级） |
| async.max-pool-size | 8 | 异步线程池最大线程数（任务级） |

> **收敛常量**（不开放配置，写死在 `ReviewService` 顶部）：`temperature=0`、`top_p=1`、`max_tokens=8192`、规则段 token 预算 `RULE_BUDGET_TOKENS=6000`。每章节单次调用（不再双采样）。这些值是"跨模型可对比"的契约本身，调动一次会让历史 `ai_result` 与新结果失去可比性，因此不放配置文件。

### 审查结果字段说明

`review_tasks.ai_result` JSON 顶层字段：

| 字段 | 含义 |
|---|---|
| `totalChunks` / `chunkResults` | 切片总数与逐切片结果 |
| `overallScore` | 平均分（模型未返回 `overall_score` 时不输出） |
| `totalIssues` / `allIssues` | 跨切片 fingerprint 去重后的问题总数与扁平列表 |
| `severityCounts` / `categoryCounts` | 按枚举（high/medium/low；格式/完整性/标准符合性/逻辑一致性/术语一致性/其他）的分桶计数 |
| `confidenceCounts` | 按 `confidence`（high/needs_review/single/single_passthrough）的分桶计数 |
| `passedRuleCoverage` | 各 `rule_code` 在 `passed_items` 中的命中次数（覆盖率指标） |
| `failedChunks` / `failedChunkCount` | 因 AI 调用失败而保留的切片，可在 UI 中点击重审 |
| `modelName` / `modelKey` | 任务使用的模型，用于跨模型对比时归类 |
| `crossModelEligible` | `false` 表示思维模型，不应参与跨模型对比 |
| `samplingStrategy` | 固定为 `single`（逐章节方案每章节单次调用） |

`allIssues[i]` 字段：

| 字段 | 含义 |
|---|---|
| `location` / `description` / `suggestion` / `rule` / `rule_code` / `evidence` | 与 prompt schema 完全一致；`rule_code` 在 manifest 之外的非法编号会被丢弃 |
| `severity` / `category` | 强制映射到枚举；无法判断 → `medium` / `其他` |
| `fingerprint` | `sha1(归一化location + "|" + rule_code)`，跨切片去重的主键 |
| `confidence` | `single`（单章节单次调用的默认值）；跨章节重复命中同一 fingerprint 时在 `aggregateResults` 中升为 `high` |
| `occurrences` | 跨切片去重后的命中次数 |
