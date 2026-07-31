# AI文档审查系统 - 文档代码对比分析报告

生成时间：2026-07-31

---

## 一、总体评估

### 1.1 文档完整性评分：★★★★☆ (4/5)

**优点：**
- README.md 提供了全面的系统概览
- 技术栈描述准确
- 双管线架构说明清晰
- 数据库设计文档完整
- SAR_PIPELINE.md 详细说明了结构化审查管线

**不足：**
- 部分API接口路径与实际代码不完全一致
- 某些新增功能未在主文档中体现
- 前端模块的详细说明较少

### 1.2 准确性评分：★★★★☆ (4/5)

**优点：**
- 核心功能描述与代码实现基本一致
- 技术栈版本号准确
- 工作流程描述正确

**需改进：**
- 部分配置参数的默认值需要更新
- API接口路径有少量不一致
- 某些模块的实现细节描述不够准确

---

## 二、发现的主要不一致点

### 2.1 API接口路径不一致

#### 问题1：审查任务提交接口
- **文档描述**：`POST /api/v1/reviews/execute`
- **实际代码**：`POST /api/v1/review/submit`
- **影响范围**：前端调用、API文档
- **建议**：统一为 `/api/v1/review/submit`

#### 问题2：规则上传冲突检查接口
- **文档描述**：`/rules/upload-conflicts`
- **实际代码**：`POST /api/v1/rules/check-conflicts`
- **影响范围**：前端调用
- **建议**：文档更新为实际路径

#### 问题3：SAR检查单导入接口
- **文档描述**：多处提到不同路径
- **实际代码**：`POST /api/v1/sar/rules/import-checklist`（在SarRuleController中）
- **建议**：在README.md中明确说明

### 2.2 配置参数差异

#### 问题1：线程池配置
- **文档描述**（application.yml说明）：
  ```yaml
  async:
    core-pool-size: 4
    max-pool-size: 8
  ```
- **实际代码**（AsyncConfig.java）：
  - reviewTaskExecutor: 核心4/最大8
  - chunkReviewExecutor: 核心20/最大50
  - sarCheckExecutor: 核心30/最大60
- **建议**：文档补充说明三个线程池的不同用途和配置

#### 问题2：审查并发度配置
- **文档描述**：`chunk-concurrency: 6`
- **实际代码**：确实为6（正确）
- **补充说明**：建议在文档中说明这是单任务内的切片并发数

### 2.3 功能模块描述不完整

#### 问题1：规则文件夹功能
- **实际代码**：已实现规则文件夹（RuleFolder实体和相关API）
- **文档描述**：README.md中未提及规则文件夹功能
- **建议**：在"功能概览"中补充说明

#### 问题2：规则元数据编辑功能
- **实际代码**：`PUT /api/v1/rules/{id}/metadata` 和 `PUT /api/v1/rules/{id}/content`
- **文档描述**：README.md中提到"规则元信息维护"，但未详细说明API
- **建议**：补充API文档说明

#### 问题3：人工复核功能
- **实际代码**：完整实现了人工复核和审计日志（ManualCheckDecisionRequest、ReviewAuditLog）
- **文档描述**：功能概览中提到，但未详细说明工作流程
- **建议**：补充人工复核流程说明

### 2.4 数据库表说明不完整

#### 问题1：problem_count字段
- **实际代码**：review_tasks和sar_review_tasks都有problem_count字段（用于性能优化）
- **文档描述**：schema.sql中有说明，但README.md未提及
- **建议**：在"数据库设计"部分说明此优化

#### 问题2：quality_check_enabled字段
- **实际代码**：review_tasks和sar_review_tasks都有此字段
- **文档描述**：README.md提到功能但未说明数据库字段
- **建议**：补充数据库字段说明

### 2.5 前端模块说明不足

#### 问题1：前端架构说明
- **实际代码**：采用features模块化架构，按业务域组织
- **文档描述**：README.md的"项目结构"部分仅简略说明
- **建议**：补充详细的前端架构说明

#### 问题2：前端核心功能
- **实际代码**：
  - 工作区使用React Virtuoso实现虚拟滚动
  - 使用Zustand管理状态（authStore、logStore）
  - WebSocket实时进度更新
  - 人工复核快捷操作
- **文档描述**：未提及这些实现细节
- **建议**：补充前端技术特性说明

---

## 三、需要更新的文档内容（按优先级）

### 高优先级（影响使用）

#### 1. 更新README.md - API接口路径

**当前内容（第150行附近）：**
```markdown
| CHUNK 审查 | /api/v1/reviews/** | 提交 `/execute`、任务 CRUD...
```

**建议修改为：**
```markdown
| CHUNK 审查 | /api/v1/review/** | 提交 `/submit`、任务 CRUD/取消/重审/导出、复核 `/manual-decision`、审计、统计 |
| SAR 审查 | /api/v1/sar-review/** | 与 CHUNK 审查同构（结构化精准管线） |
```

#### 2. 补充规则文件夹功能说明

**在README.md第14行"规则库管理"后补充：**
```markdown
- **规则文件夹**：规则库内可创建二级文件夹，按规则类型（通用/专项）分类管理；支持整个文件夹启用/停用
```

#### 3. 更新配置说明

**在README.md第344行"async配置"部分补充：**
```markdown
## 线程池架构（三级隔离避免死锁）

系统使用三个独立线程池：

1. **reviewTaskExecutor**（任务级）
   - 核心：4，最大：8，队列：100
   - 用途：@Async任务启动（一个审查任务 = 一个async方法调用）

2. **chunkReviewExecutor**（切片级）
   - 核心：20，最大：50，队列：100
   - 用途：CHUNK管线单章节并行审查
   - 配置：由 `review.parallel.chunk-concurrency` 控制单任务并发度

3. **sarCheckExecutor**（检查项级）
   - 核心：30，最大：60，队列：100
   - 用途：SAR管线检查项并行评估
   - 配置：由 `review.sar.check-concurrency` 控制
```

### 中优先级（完善说明）

#### 4. 补充前端架构说明

**在README.md第100行"前端"部分补充：**
```markdown
├── frontend/
│   ├── src/
│   │   ├── app/                      # 应用入口和路由配置
│   │   ├── features/                 # 业务功能模块（按域组织）
│   │   │   ├── auth/                 # 认证模块（登录/注册、JWT、authStore）
│   │   │   ├── dashboard/            # 工作台（任务列表、统计、新建审查）
│   │   │   ├── review/               # 审查模块
│   │   │   │   ├── pages/            # ReviewWorkspacePage
│   │   │   │   ├── workspace/        # 工作区核心逻辑（useReviewWorkspace Hook）
│   │   │   │   ├── components/       # 审查相关组件（FileUploader等）
│   │   │   │   ├── api/              # 审查API（reviews.ts、sarReviews.ts、pipelineApi.ts）
│   │   │   │   └── store/            # logStore（WebSocket日志累积）
│   │   │   ├── rules/                # 规则管理（RuleListPage、RuleUploader）
│   │   │   ├── scenarios/            # 场景管理（ScenarioListPage）
│   │   │   ├── modelConfig/          # 模型配置（ModelConfigPage）
│   │   │   └── users/                # 用户管理（UserManagementPage、ProfilePage）
│   │   ├── shared/                   # 共享模块
│   │   │   ├── api/                  # Axios封装（request.ts、统一拦截器、token刷新）
│   │   │   ├── components/           # 公共组件（AppLayout、ProtectedRoute）
│   │   │   ├── utils/                # 工具函数（constants.ts、websocket.ts）
│   │   │   └── styles/               # 全局样式
│   │   ├── main.tsx                  # Vite入口
│   │   └── vite-env.d.ts

### 前端技术特性
- **虚拟滚动**：审查工作区使用 react-virtuoso 优化长列表性能
- **状态管理**：Zustand（轻量级）管理认证状态和日志状态
- **实时通信**：WebSocket 订阅任务进度和日志，跨页面持久化
- **双管线架构**：通过路由和 reviewMode prop 区分 CHUNK/SAR
- **管线派发层**：pipelineApi.ts 统一派发逻辑，避免组件中散落条件判断
- **Token刷新**：全局单例Promise避免并发401时重复刷新
```

#### 5. 补充人工复核流程说明

**在README.md第23行"结果导出"后补充：**
```markdown
- **人工复核**：支持对系统判定进行人工确认、改判或驳回，记录完整审计日志（操作人、时间、前后值）；复核结果实时同步到导出文件
```

#### 6. 完善API接口文档

**在README.md第274行"API接口"表格中补充：**
```markdown
| 模块 | 路径 | 说明 |
|------|------|------|
| 认证（共享） | /api/v1/auth/** | 注册、登录、刷新 Token（无需认证） |
| CHUNK 审查 | /api/v1/review/** | 提交 `/submit`、详情 `/{id}` 和 `/{id}/light`、源文件 `/{id}/sources`、列表查询、取消 `/{id}/cancel`、重审 `/{id}/retry-failed`、人工复核 `/{id}/manual-decision`、审计日志 `/{id}/audit-logs` |
| CHUNK 场景/规则/规则库 | /api/v1/scenarios·rules·rule-libraries/** | CRUD操作、规则上传 `/rules/upload`、冲突检查 `/rules/check-conflicts`、元数据编辑 `/rules/{id}/metadata`、内容编辑 `/rules/{id}/content` |
| 规则文件夹 | /api/v1/rule-libraries/{id}/folders/** | 创建、列表、更新、启用/禁用 `/folders/{id}/toggle` |
| SAR 审查 | /api/v1/sar-review/** | 与 CHUNK 审查同构（结构化精准管线） |
| SAR 场景/规则/规则库 | /api/v1/sar/scenarios·rules·rule-libraries/** | SAR 侧；Excel 检查单导入 `/sar/rules/import-checklist`；规则上传、冲突检查、元数据/内容编辑 |
| SAR 规则文件夹 | /api/v1/sar/rule-libraries/{id}/folders/** | 与CHUNK侧对称 |
| 跨管线 | /api/v1/unified-review/** | 合并列表 `/tasks`（?mode=ALL\|CHUNK\|SAR）、合并统计 `/stats`、按ID查询 `/tasks/{id}` |
| 导出 | /api/v1/review/export/{id}/** | Excel `/excel`、Word报告 `/report`、审计日志 `/audit` |
| 模型（共享） | /api/v1/models/** | CRUD、连接测试 `/test-connection`、思考模式建议 `/suggest-thinking-mode`、启用列表 `/enabled?modelType=`、启停 `/{id}/toggle` |
| 用户管理（共享） | /api/v1/users/** | 用户 CRUD、规则库分配 `/assign-libraries`（带 ?mode=CHUNK\|SAR）、已分配库查询 `/assigned-libraries` |
| 个人（共享） | /api/v1/user/** | 个人信息 `/profile`、改密 `/change-password` |
| 仪表盘（共享） | /api/v1/admin/dashboard | 系统统计（仅主管/管理员） |
| 健康检查 | /api/health | 容器健康检查 |
```

### 低优先级（锦上添花）

#### 7. 补充性能优化说明

**在README.md末尾新增章节：**
```markdown
## 性能优化

### 后端优化
- **问题数缓存**：review_tasks.problem_count 字段缓存问题总数，工作台列表查询无需反序列化大型 ai_result JSON
- **懒加载**：详情页提供 light 模式（/tasks/{id}/light），不含源文件；源文件按需加载（/tasks/{id}/sources）
- **三级线程池**：任务/切片/检查项三级隔离避免死锁
- **批量向量化**：SAR管线嵌入批大小24条/批
- **HNSW索引**：pgvector使用HNSW索引加速向量检索

### 前端优化
- **虚拟滚动**：审查工作区检查项列表使用 Virtuoso 虚拟滚动
- **WebSocket缓存**：进度数据内存缓存，页面刷新后立即恢复
- **Token刷新优化**：全局单例Promise防止并发401时重复刷新
- **懒加载原文**：结构化原文按需加载，避免初次加载过大
```

#### 8. 补充开发指南

**在README.md末尾新增章节：**
```markdown
## 开发指南

### 新增审查管线
如需添加新的审查管线（如 XYZ 管线）：

1. **数据库**：在 schema.sql 中创建 xyz_* 表（参考 sar_* 表结构）
2. **后端Entity**：创建 XyzReviewTask、XyzRule 等实体类
3. **后端Service**：实现 XyzReviewService（核心审查逻辑）
4. **后端Controller**：创建 XyzReviewController（API端点：/api/v1/xyz-review/**）
5. **前端API**：创建 xyzReviews.ts 和 xyzRules.ts
6. **前端路由**：在 App.tsx 中添加 /xyz/scenarios 和 /xyz/rules 路由
7. **前端UI**：在 AppLayout 中添加侧边栏入口
8. **统一接口**：在 UnifiedReviewController 和 pipelineApi.ts 中添加 XYZ 模式支持

### 新增规则类型
如需添加新的规则类型：

1. 在 RuleMetadata 中添加新的 rule_type 枚举值
2. 在 RuleDispatcher.dispatchForChunk() 中实现调度逻辑
3. 更新 ReviewResultSchema 中的规则清单构建逻辑
4. 前端 RuleListPage 中添加类型选项

### 新增AI模型厂商
如需接入新的AI厂商：

1. 在 AiModelConfig 的 provider 字段添加新值
2. 在 EndpointResolver 中实现端点解析逻辑（如需特殊处理）
3. 在 AiModelService 中添加厂商特定逻辑（如需）
4. 前端 ModelConfigPage 的供应商下拉中添加选项
```

---

## 四、具体修改建议

### 4.1 README.md修改清单

**第14-25行（功能概览部分）：**

现有内容：
```markdown
- **双管线文档审查**：CHUNK 全文逐章 / SAR 结构化精准，前端 Tab 切换；上传 Word 文档（.doc/.docx）自动审查并生成报告
- **规则库管理**：上传 Markdown 或 JSON 格式规则文件，按规则库分类管理（CHUNK / SAR 各一套）...
```

建议修改为：
```markdown
- **双管线文档审查**：CHUNK 全文逐章 / SAR 结构化精准，前端 Tab 切换；上传 Word 文档（.doc/.docx）自动审查并生成报告
- **规则库管理**：上传 Markdown 或 JSON 格式规则文件，按规则库分类管理（CHUNK / SAR 各一套）；CHUNK 侧 Markdown 支持按 `##` 规则块拆分，多条规则可来自同一文件；SAR 侧支持 Excel 检查单导入（`/sar/rules/import-checklist`）拆解为原子检查项
- **规则文件夹**：规则库内可创建二级文件夹，按规则类型（通用/专项）分类管理；支持整个文件夹启用/停用
- **规则上传增量合并**：重复上传规则文件时，系统按同一规则库/文件夹内的规则编号优先、规则名称兜底自动覆盖已有规则；文件中新增加的规则直接追加到库中
- **规则元信息维护**：规则名称、编号、类型、适用章节、关键词、描述可在 UI 编辑；仅编辑元信息不会刷新规则排序时间，保存后列表位置保持不变
- **审查场景配置**：将多个规则库组合为审查场景，针对不同业务场景灵活配置
- **检查项判定矩阵**：以上传规则/原子检查项为单位输出三级判定（Pass / Fail / Review）+ 证据 + 缺失项 + 建议；CHUNK 会强制每条上传业务规则至少返回一条判定并写入规则快照，内置 `R-Q` 文字质量/图表编号检查仅在 Fail/Review 时显示，支持人工确认/改判与审计日志
- **人工复核**：支持对系统判定进行人工确认、改判或驳回，记录完整审计日志（操作人、时间、前后值）；复核结果实时同步到导出文件
- **全文术语一致性**：CHUNK 每个切片会筛选专业术语并生成 `术语表_*.json`，再基于术语表追加一次全文术语一致性审查，为后续维护标准术语库预留数据
- **多模型支持**：按用途区分 chat / embedding / reranker 模型，接入 OpenAI、Anthropic、Moonshot、百度、阿里、讯飞等主流 AI 厂商，支持自定义厂商
- **实时进度追踪**：WebSocket 实时推送审查进度、日志与结果
- **角色权限控制**：项目主管 / 管理员 / 普通用户三级权限体系
- **结果导出**：检查项判定矩阵 Excel、审查报告 Word（.docx）、审计 JSON
- **用户管理**：用户注册、登录、密码修改、按管线分别分配规则库
```

**第288-299行（API接口表格）：**

按照前面"4.1节-API接口文档"的建议更新整个表格。

**第326-346行（配置说明）：**

按照前面"3.更新配置说明"的建议补充线程池架构说明。

### 4.2 SAR_PIPELINE.md修改清单

**第6行（检查单导入接口）：**

现有内容：
```markdown
> - **检查单导入**：接口为 **`POST /api/v1/sar/rules/import-checklist`**（不再是 `/api/v1/rules/...` 或旧 RAG 路径）。
```

确认正确，无需修改。

### 4.3 试验大纲智能审查处理流程.md修改清单

**第5行（检查单导入接口）：**

现有内容：
```markdown
> - **结构化精准审查（SAR）**——三路（结构+词法+语义）定位预期区域、区域级取证、清单式缺失检测、自适应复核、跨章一致性。由 `SarReviewService` 实现，检查单导入在 **`POST /api/v1/sar/rules/import-checklist`**。
```

确认正确，无需修改。

---

## 五、代码与文档一致的部分（无需修改）

以下方面文档描述与代码实现完全一致：

### 5.1 核心架构
✅ 双管线架构（CHUNK / SAR）正确
✅ 前后端分离架构正确
✅ Spring Boot + React 技术栈正确
✅ PostgreSQL + pgvector 数据库正确

### 5.2 技术栈版本
✅ Java 17
✅ Spring Boot 3.2.5
✅ React 18.3
✅ TypeScript 5.5
✅ PostgreSQL 16 + pgvector 0.8.2
✅ MyBatis-Plus 3.5.5

### 5.3 核心功能
✅ Word文档解析（.doc/.docx）
✅ 章节切片和Token估算
✅ 规则库和审查场景管理
✅ 多模型接入
✅ WebSocket实时进度
✅ JWT认证（access + refresh token）
✅ 三级角色权限（supervisor / admin / user）
✅ Excel/Word/JSON导出

### 5.4 数据库设计
✅ 双管线物理隔离（CHUNK表 + SAR表）
✅ pgvector向量检索支持
✅ JSONB字段存储复杂结构
✅ 审计日志表设计

### 5.5 工作流程
✅ CHUNK管线流程描述准确
✅ SAR管线流程描述准确
✅ 规则调度机制正确
✅ 跨章节引用处理正确

---

## 六、执行建议

### 立即执行（高优先级）
1. 更新README.md中的API接口路径
2. 补充规则文件夹功能说明
3. 更新线程池配置说明

### 近期执行（中优先级）
4. 补充前端架构详细说明
5. 补充人工复核流程说明
6. 完善API接口文档表格

### 后续优化（低优先级）
7. 补充性能优化说明章节
8. 补充开发指南章节

---

## 七、总结

总体而言，项目文档质量较高，核心架构和功能描述准确。主要需要改进的是：

1. **API接口路径统一**：少数接口路径文档与代码不一致，需要更新
2. **新增功能补充**：规则文件夹、人工复核等功能已实现但文档未详细说明
3. **实现细节完善**：前端架构、性能优化等实现细节可以补充说明
4. **配置参数说明**：线程池等配置需要更详细的说明

建议优先处理高优先级的修改，确保用户能够正确使用系统；然后逐步完善中低优先级的内容，提升文档完整性。

---

**报告结束**
