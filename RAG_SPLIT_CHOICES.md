# RAG / CHUNK 双管线拆分 — 实现说明与决策记录

> **后续更新**：本次 RAG/CHUNK 拆分之后，又新增了第三套并列管线 **结构化精准审查（SAR）**，与 `rag_*` 结构对称、物理隔离（表前缀 `sar_`、路由 `/api/v1/sar`、`reviewMode=SAR`），沿用本文同一套隔离范式（独立规则库/规则/场景/任务表 + 前端 Tab/侧边栏第三入口 + `UnifiedReviewController` 三表合并 + `UserService` 按 mode 分配）。SAR 引擎（三路路由 + 区域取证 + 清单缺失 + 自适应复核 + 跨章一致性）与调参见 [SAR_PIPELINE.md](SAR_PIPELINE.md)。下文内容仍描述 RAG/CHUNK 两条管线。

本次重构把后端原本通过 `review.rag.enabled` 配置项二选一的两条审查管线，改造为前端二选一、后端物理隔离的并列模式。本文档记录 Stage 3 → Stage 6 实施过程中我（Claude）替你做的所有判断，以及做出该判断的理由。请重点 review **"我替你拍板的事项"** 一节，发现不符合预期的可在原代码上直接调整或反馈给我重做。

> 用户决策（已在前几轮对话明确确认）：
> 1. 命名：智能召回审查 / 全文逐章审查
> 2. 隔离粒度：粗粒度（规则库 / 规则 / 场景全部分两套）
> 3. 现有数据归属：扫描 `rule_checks` 表，含原子检查项的规则归 RAG，其余归 CHUNK
> 4. 新建审查对话框：顶部 Tab 切换
> 5. 工作台任务列表：列 + 顶部筛选器 + 角标
> 6. 模型管理：UI 不限制，按 `model_type` 自动过滤
> 7. 后端表：新建一组 RAG 表，与现有表物理隔离
> 8. 默认管线：无默认，强制手动选；历史任务按 `ai_result.reviewMode` 推断，缺失填 CHUNK

---

## 一、项目结构变更总览

### 后端新增（24 个文件）

```
backend/src/main/java/com/aireview/
├── entity/
│   ├── RagRuleLibrary.java          ← 复制自 RuleLibrary，@TableName 改为 rag_rule_libraries
│   ├── RagRule.java
│   ├── RagRuleCheck.java
│   ├── RagScenario.java
│   ├── RagScenarioRuleMapping.java
│   ├── RagScenarioLibraryMapping.java
│   ├── RagUserRuleAssignment.java
│   ├── RagReviewTask.java
│   ├── RagReviewAuditLog.java
│   └── RagDocumentBlock.java
├── repository/                      ← 10 个对应 Mapper
│   ├── RagRuleLibraryMapper.java
│   ├── RagRuleMapper.java
│   ├── RagRuleCheckMapper.java
│   ├── RagScenarioMapper.java
│   ├── RagScenarioRuleMappingMapper.java
│   ├── RagScenarioLibraryMappingMapper.java
│   ├── RagUserRuleAssignmentMapper.java
│   ├── RagReviewTaskMapper.java
│   ├── RagReviewAuditLogMapper.java
│   └── RagDocumentBlockMapper.java
├── service/
│   ├── RagRuleLibraryService.java
│   ├── RagRuleService.java
│   ├── RagScenarioService.java
│   └── ReviewExportUtil.java        ← Excel/Docx 导出共享工具
└── controller/
    ├── RagRuleLibraryController.java   /api/v1/rag/rule-libraries/**
    ├── RagRuleController.java           /api/v1/rag/rules/**（含 /import-checklist）
    ├── RagScenarioController.java       /api/v1/rag/scenarios/**
    ├── RagReviewController.java         /api/v1/rag/reviews/**
    └── UnifiedReviewController.java     /api/v1/reviews/all、/stats/all、/by-id/{taskId}
```

### 后端删除

- `entity/DocumentBlock.java`、`repository/DocumentBlockMapper.java`：原本是 RAG 专用，被 `RagDocumentBlock` 取代

### 后端改造（关键点）

| 文件 | 改动 |
|---|---|
| `service/RagReviewService.java` | 切到 RAG mapper/entity；新增 14 个 task CRUD/导出/复核方法；新增 `@Async executeReviewAsync(taskId)` 入口 |
| `service/ReviewService.java` | 删 RAG 分流分支；删 `review.rag.enabled` 注入；exports 改为 delegate 给 `ReviewExportUtil` |
| `service/ChecklistRuleImportService.java` | 注入 `RagRuleService` 而不是 `RuleService`（checklist 产物属于 RAG） |
| `service/UserService.java` | 注入 `RagUserRuleAssignmentMapper`；新增 `assignLibrariesByMode` / `getAssignedLibraryIdsByMode`；`deleteUser` 时同时清理两侧分配 |
| `repository/DocumentVectorRepository.java` | SQL 表名 `document_blocks → rag_document_blocks`；索引前缀 `idx_doc_blocks_ → idx_rag_doc_blocks_` |
| `controller/RuleController.java` | 删除 `/import-checklist`（迁到 `RagRuleController`） |
| `controller/UserManagementController.java` | `assignLibraries` 与 `getUserLibraries` 增加 `mode` query 参数 |
| `review/migration/PromptsMigrationRunner.java` | SQL 改为查 `rag_rules` / 写 `rag_rule_checks` |
| `resources/schema.sql` | 追加 14 张 `rag_*` 表 + 14 个索引 + 幂等迁移块（详见下节） |
| `resources/application.yml` | 删 `review.rag.enabled` / `fallback-to-legacy`；其余 `review.rag.*` 保留 |
| `dto/ReviewTaskDTO.java` | 新增 `reviewMode: 'CHUNK' \| 'RAG'` 字段 |

### 前端新增

```
frontend/src/api/
├── ragScenarios.ts        ← /rag/scenarios/**
├── ragRules.ts            ← /rag/rules/**、/rag/rule-libraries/**
├── ragReviews.ts          ← /rag/reviews/**
└── pipelineApi.ts         ← 按 mode 派发的 dispatch helper + unified endpoints
```

### 前端改造

| 文件 | 改动 |
|---|---|
| `components/AppLayout.tsx` | 侧边栏拆为 RAG / CHUNK 两个分组，4 条规则与场景子项 |
| `App.tsx` | 新增 `/rag/scenarios`、`/rag/rules`、`/chunk/scenarios`、`/chunk/rules`；旧 `/scenarios`、`/rules` 重定向到 `/chunk/*` |
| `pages/ScenarioListPage.tsx` | 接受 `reviewMode` prop，通过 `getScenarioApi(mode)` 派发 API |
| `pages/RuleListPage.tsx` | 同上，新增 mode 切换时重置状态的副作用 |
| `pages/DashboardPage.tsx` | 列表改用 unified endpoint；新增审查方式列 + 顶部 mode 筛选器；"新建审查" 对话框改为顶部 Tab 切换 |
| `pages/ReviewWorkspacePage.tsx` | 通过 `getReviewDetailAnyPipeline` 初始拉取；后续所有调用走 `getReviewApi(task.reviewMode).x` |
| `pages/UserManagementPage.tsx` | 规则库分配对话框新增 Radio 切换 RAG / CHUNK |
| `api/reviews.ts` | `ReviewTask` 新增 `reviewMode` 字段、新增 `ReviewMode` 类型 |
| `api/users.ts` | `assignLibraries` / `getUserAssignedLibraries` 接受 mode 参数 |

---

## 二、数据库设计

### 哪些表新增了
14 张：`rag_rule_libraries` / `rag_rules` / `rag_rule_checks` / `rag_rule_check_examples` / `rag_scenarios` / `rag_scenario_rule_mapping` / `rag_scenario_library_mapping` / `rag_user_library_assignment` / `rag_review_tasks` / `rag_review_audit_logs` / `rag_document_blocks` / `rag_review_pipelines` / `rag_review_findings` / `rag_ai_call_logs`。

### 哪些表保留作 CHUNK 专用
`rule_libraries` / `rules` / `scenarios` / `scenario_*_mapping` / `user_library_assignment` / `review_tasks` / `review_audit_logs`。

### 哪些表迁移完成后会留空（不删除）
`rule_checks` / `rule_check_examples` / `document_blocks` / `review_pipelines` / `review_findings` / `ai_call_logs`：原 RAG 专用，迁移后数据已搬到 `rag_*` 同名表，仅保留空壳避免 schema drift 报错。

### 迁移脚本（位于 `schema.sql` 末尾的 `DO $rag_migration$ ... END $rag_migration$`）

幂等：开头有双重哨兵——
1. `rag_rule_libraries` 已有数据 → 直接 `RETURN`（防止重启重复迁移）
2. 没有 `rule_checks` 且没有 `ai_result.reviewMode='rag'` 的历史任务 → 直接 `RETURN`（全新部署无事可做）

判定规则：
- **库级 RAG 候选** = 任何包含 `rule_checks` 的规则所在库
- **场景级 RAG 候选** = 映射到任意 RAG 库的场景
- **库集合扩张** = 这些场景所关联的全部其他库（保住场景完整性）
- **任务级 RAG 候选** = `ai_result->>'reviewMode' = 'rag'` 的 review_tasks
- **兜底** = RAG 任务引用的 scenario_id 一并并入；这些场景关联的库一并并入（防御 rule_checks 后被删导致库级判定漏掉）

迁移完成后：
- INSERT 复制时显式带 id（保留外键稳定）
- `setval()` 把所有 `rag_*` sequence 推进到 `MAX(id)+1`
- DELETE 利用 `ON DELETE CASCADE` 清原表，最后做一轮安全清场清掉 orphan `rule_checks` 等

---

## 三、API 路由总图

```
/api/v1/auth/**                       共享
/api/v1/user/**                       共享（个人信息、改密）
/api/v1/admin/users/**                共享（用户管理，?mode=RAG|CHUNK 控制库分配）
/api/v1/models/**                     共享（模型配置）
/api/v1/health                        共享

# CHUNK 管线
/api/v1/rules/**                      规则
/api/v1/rule-libraries/**             规则库
/api/v1/scenarios/**                  场景
/api/v1/reviews/execute               提交
/api/v1/reviews/tasks/**              CRUD / 取消 / 重审 / 导出
/api/v1/reviews/stats                 stats

# RAG 管线
/api/v1/rag/rules/**                  规则（含 /import-checklist）
/api/v1/rag/rule-libraries/**         规则库
/api/v1/rag/scenarios/**              场景
/api/v1/rag/reviews/execute           提交
/api/v1/rag/reviews/tasks/**          CRUD / 取消 / 重审 / 导出
/api/v1/rag/reviews/stats             stats

# 跨管线（dashboard 使用）
/api/v1/reviews/all                   合并列表（query: mode=ALL|CHUNK|RAG, status=...）
/api/v1/reviews/stats/all             合并统计（含 byMode 子对象）
/api/v1/reviews/by-id/{taskId}        管线未知时的任务详情查询（先查 CHUNK 表，找不到回退 RAG）
/api/v1/reviews/by-id/{taskId}/sources 管线未知时的原文/来源懒加载（同样双表试查）
```

---

## 四、我替你拍板的事项

下面这些是 Stage 3-6 期间我没有再问你、直接做出的选择，每条都附了"为什么这样选"和"如果你想改怎么改"。

### 1. RAG 侧的 `retryFailedChunks` 行为

| 选项 | 是否选 |
|---|---|
| 与 CHUNK 一样选择性重审失败的切片 | ✗ |
| **全量重新跑一遍**（等同于 `reReview`） | ✓ |

**原因**：RAG 管线已经内置 `failedCheckRetryAttempts`，每条 check 单独调用，选择性重试需要从 `aiResult.allCheckResults` 里挑出 `retryExhausted=true` 的项再单独跑，bookkeeping 成本比 CHUNK 高、收益小（一次 RAG 全量重跑的成本就是 ~M 次 LLM 调用，比 CHUNK 的全量重跑便宜很多）。

**修改方式**：`RagReviewService.retryFailedChunks` 现在直接调 `self.executeReviewAsync(taskId)`，要改成选择性可在该方法里读 `task.getAiResult().get("allCheckResults")`，筛出 `retryExhausted=true` 的项后在 `executeReview` 之前传入。

### 2. Excel/Docx 导出：抽公共类还是各自重写

| 选项 | 是否选 |
|---|---|
| 让 `RagReviewService` 复制 ~150 行 Excel/Docx 写入逻辑 | ✗ |
| **抽 `ReviewExportUtil` 静态工具类，两边都 delegate** | ✓ |

**原因**：导出格式只看 `aiResult` Map 的形状，与是哪条管线无关；CHUNK 与 RAG 的 `aiResult` 字段名兼容。

**副作用**：`ReviewService` 原本的 `addDocParagraph` / `writeIssueRow` / `renderCheckStatus` 等私有静态被删，全部转到 `ReviewExportUtil`；`firstNonBlank` / `strField` / `normalizeCheckStatus` 在 `ReviewService` 内部仍被多处复用，保留了一份私有副本。

### 3. 任务列表跨管线合并的实现

| 选项 | 是否选 |
|---|---|
| 后端 SQL UNION ALL | ✗ |
| **后端两次 SELECT + 内存合并 + 排序 + 分页** | ✓ |

**原因**：CHUNK 与 RAG 任务表的主键虽然都是 `VARCHAR(36)`，但表结构互独立、Mapper 类型也独立，UNION 要写裸 SQL；而 dashboard 只看最近 200 条以内，两次 mapper 调用的延迟可忽略；好处是任务列表的过滤逻辑（status / mode）全用 Java 写，可维护性比 SQL 高。

**修改方式**：见 `UnifiedReviewController.PER_PIPELINE_FETCH = 200`。如果你的总任务量 > 200 条、且需要跨管线分页深翻，把这个常量改大或改写为 SQL UNION 都可以。

### 4. 跨管线任务详情的路由策略

| 选项 | 是否选 |
|---|---|
| URL 带管线标识 `/review/chunk/<id>` `/review/rag/<id>` | ✗ |
| **保留 `/review/<id>`；后端 `/reviews/by-id/{taskId}` 双表试查** | ✓ |

**原因**：维持旧链接兼容；前端工作台拿到 `task.reviewMode` 后所有后续调用都走对应管线，开销只在初始一次拉取。

**修改方式**：`UnifiedReviewController.getTaskAnyPipeline` 先查 CHUNK 后查 RAG，如果你想改顺序或加缓存，改这一个方法即可。

### 5. 新建审查对话框：场景列表是否合并

| 选项 | 是否选 |
|---|---|
| 一个场景下拉框，显示所有场景，按 reviewMode 给场景打标签 | ✗ |
| **Tab 切换；Tab 内场景下拉只显示对应管线的场景** | ✓ |

**原因**：粗粒度隔离的精神就是"两条管线互不可见"，合并下拉会让用户在 RAG 任务里能选到 CHUNK 场景（被后端拒绝，体验差）。

### 6. 新建审查对话框：默认选中哪个管线

| 选项 | 是否选 |
|---|---|
| 默认选 RAG | ✗ |
| 默认选 CHUNK | ✗ |
| **不选任何，强制用户手动点 Tab** | ✓ |

**原因**：你在决策 #8 明确要求"手动下拉选择"。对话框打开时显示一个 `Alert` 提示"请先在上方选择审查方式"，未选时不渲染下方步骤。

### 7. RAG 模式下的模型选择

| 选项 | 是否选 |
|---|---|
| 让用户在对话框里选 chat + embedding + reranker 三个模型 | ✗ |
| **只选 chat 模型；embedding / reranker 由后端用"第一个 enabled 的"自动挑** | ✓ |

**原因**：符合决策 #6（UI 不限制、后端按类型过滤）；保留 `AiModelService.getFirstEnabledModelByType` 的现有行为。如果未启用任何 embedding 模型，前端会在 Tab 切到 RAG 时显示醒目的 warning Alert + 提交时阻止。

### 8. 用户管理页的规则库分配

| 选项 | 是否选 |
|---|---|
| 假定 supervisor 不需要为 RAG 单独分配（regular user 默认看不到 RAG） | ✗ |
| **同一对话框内用 Radio 切换 RAG / CHUNK，分别保存** | ✓ |

**原因**：粗粒度隔离会让 `RagRuleService.listLibraries` 对 `role=user` 的用户只显示 `rag_user_library_assignment` 里有的库，如果不给 supervisor 一个分配入口，普通用户在 RAG 侧一条规则都看不到。Radio 比开两个对话框更紧凑。

### 9. 历史任务 `ai_result.reviewMode` 缺失时的归属

- **后端迁移**：迁移脚本只搬 `ai_result->>'reviewMode' = 'rag'` 的任务，其余留在 CHUNK 侧（决策 #8）
- **前端展示**：列表里历史任务的 `reviewMode` 字段如果是 `undefined`，UI 一律渲染为"全文逐章审查"（CHUNK），并把后续操作（取消/重审/删除/导出）都走 CHUNK API

如果某个历史任务实际上是 RAG 跑的但 `reviewMode` 字段缺失（罕见），它会被错误地归到 CHUNK 列表，导致 CHUNK API 调用 404。处理办法：直接在 DB 给它 `UPDATE review_tasks SET ai_result = jsonb_set(ai_result, '{reviewMode}', '"rag"') WHERE id = '...'`，然后 re-run 迁移（删空 `rag_rule_libraries` 触发哨兵失效后再启动一次）。

### 10. 现有 `RuleCheck` / `RuleCheckMapper` 是否保留

| 选项 | 是否选 |
|---|---|
| 删除（chunk 侧不应再有 rule_checks） | ✗ |
| **保留**（chunk `ReviewService` 仍依赖 `attachChecks` → `ruleCheckMapper.findActiveByRuleIds`） | ✓ |

**原因**：删 `RuleCheck` 涉及 `ReviewService` ~50 行连带删除，风险高且无明显收益；保留后 `rule_checks` 表是空的，`findActiveByRuleIds` 总返回空列表，chunk 管线的 prompt 也就只用 `rule.content` 不带 atomic check entries，行为正确。

### 11. 没有做的事（已知缺口）

- **跨管线规则复制工具**：决策时已确认"不在本次范围"。
- **管线性能对比报表**：同上。
- **跨用户的 RAG 库批量赋权**：UserManagementPage 只支持一次给一个用户分配；想批量需要新接口。
- **`output/` 下历史 JSON 文件不区分管线**：依然按 `审查结果_<file>_<model>_<ts>.json` 命名；想区分可在 `saveAiResultToFile` 前缀加 `RAG_` 或 `CHUNK_`。
- **RagReviewService（现约 1770 行）**：把 task CRUD 与 pipeline 写在同一个类里是有意为之（贴合 chunk 侧 `ReviewService` 现约 2430 行的风格）；想抽 `RagReviewTaskService` 完全可以、但不属于本次目标。注：RAG 检索/评估管线在本次拆分后又做过一次"按章节分组评估"的降本改造，当前算法与参数以 [RAG_RECALL_TUNING.md](RAG_RECALL_TUNING.md) 为准。
- **WebSocket 任务进度的管线归属**：`taskWebSocket` 按 task_id 广播，UUID 全局唯一，无需改动。但前端日志面板（`useLogStore`）的日志条目里没有管线标签，如果以后要做"按管线过滤日志"还需要新加字段。

---

## 五、本地验证

### 我已经做过的检查
- 后端：grep 全代码确认无 `RagRagXxx` 双前缀、无残留 `ragReviewService` 注入、无残留 `review.rag.enabled` 引用
- 前端：`tsc --noEmit -p tsconfig.json` 通过，无类型错误

### 我没法在本地做的检查
- 后端 Maven 编译（本机无 mvn；JDK 25 与 Lombok 1.18.36 注解处理器不兼容，无法直接 javac）
- 端到端运行（Docker daemon 未启动）

### 推荐的验证步骤
```bash
# 1. 拉新镜像 + 跑迁移
docker compose down -v
docker compose up -d --build

# 2. 启动后从 backend.log 找这两行确认迁移成功
docker compose logs backend | grep "RAG split migration"
# 应看到: NOTICE:  RAG split migration: starting
#         NOTICE:  RAG split migration: completed

# 3. 用 admin_root / admin_root 登录，左侧应看到两个分组：
#    - 智能召回审查 → 审查场景 / 审查规则
#    - 全文逐章审查 → 审查场景 / 审查规则

# 4. 测试场景（任选一条管线即可）
#    - 在该管线下创建规则库 → 上传规则 → 创建场景
#    - 在工作台点"新建审查"→ 选对应 Tab → 上传 Word → 提交

# 5. 历史任务确认
#    - 任务列表里旧任务的"审查方式"列应显示"全文逐章审查"（除非 ai_result.reviewMode='rag'）
```

### 已知风险点
1. **JDK 25 + Lombok**：项目 pom.xml 写 Java 17，本机 Java 25 不可用。Docker 镜像里用 JDK 17，应该没问题；但如果你 IDE 用 JDK 25 跑会报 Lombok 兼容错误。
2. **迁移脚本性能**：如果你的 review_tasks 表有 > 10000 行，迁移 INSERT 会需要几分钟。可以容忍 — schema.sql 只在每次启动时跑一次，哨兵会拦住后续启动。
3. **前端旧 URL 重定向**：`/scenarios` → `/chunk/scenarios`、`/rules` → `/chunk/rules`。如果你在外部系统里硬编码了 `/scenarios` 链接而 RAG 才是想要的，需要手动改。

---

## 六、文件清单（含行数）

```
# 后端新增
backend/src/main/java/com/aireview/entity/RagRuleLibrary.java                 (~25)
backend/src/main/java/com/aireview/entity/RagRule.java                         (~45)
backend/src/main/java/com/aireview/entity/RagRuleCheck.java                    (~40)
backend/src/main/java/com/aireview/entity/RagScenario.java                     (~17)
backend/src/main/java/com/aireview/entity/RagScenarioRuleMapping.java          (~18)
backend/src/main/java/com/aireview/entity/RagScenarioLibraryMapping.java       (~18)
backend/src/main/java/com/aireview/entity/RagUserRuleAssignment.java           (~18)
backend/src/main/java/com/aireview/entity/RagReviewTask.java                   (~45)
backend/src/main/java/com/aireview/entity/RagReviewAuditLog.java               (~45)
backend/src/main/java/com/aireview/entity/RagDocumentBlock.java                (~45)
backend/src/main/java/com/aireview/repository/RagRuleLibraryMapper.java        (~10)
backend/src/main/java/com/aireview/repository/RagRuleMapper.java               (~20)
backend/src/main/java/com/aireview/repository/RagRuleCheckMapper.java          (~25)
backend/src/main/java/com/aireview/repository/RagScenarioMapper.java           (~10)
backend/src/main/java/com/aireview/repository/RagScenarioRuleMappingMapper.java (~20)
backend/src/main/java/com/aireview/repository/RagScenarioLibraryMappingMapper.java (~20)
backend/src/main/java/com/aireview/repository/RagUserRuleAssignmentMapper.java (~20)
backend/src/main/java/com/aireview/repository/RagReviewTaskMapper.java         (~10)
backend/src/main/java/com/aireview/repository/RagReviewAuditLogMapper.java     (~20)
backend/src/main/java/com/aireview/repository/RagDocumentBlockMapper.java      (~20)
backend/src/main/java/com/aireview/service/RagRuleLibraryService.java          (~100)
backend/src/main/java/com/aireview/service/RagRuleService.java                 (~290)
backend/src/main/java/com/aireview/service/RagScenarioService.java             (~130)
backend/src/main/java/com/aireview/service/ReviewExportUtil.java               (~320)
backend/src/main/java/com/aireview/controller/RagRuleLibraryController.java    (~110)
backend/src/main/java/com/aireview/controller/RagRuleController.java           (~130)
backend/src/main/java/com/aireview/controller/RagScenarioController.java       (~110)
backend/src/main/java/com/aireview/controller/RagReviewController.java         (~230)
backend/src/main/java/com/aireview/controller/UnifiedReviewController.java     (~130)

# 后端删除
backend/src/main/java/com/aireview/entity/DocumentBlock.java
backend/src/main/java/com/aireview/repository/DocumentBlockMapper.java

# 前端新增
frontend/src/api/ragScenarios.ts                                              (~35)
frontend/src/api/ragRules.ts                                                  (~70)
frontend/src/api/ragReviews.ts                                                (~70)
frontend/src/api/pipelineApi.ts                                               (~120)

# 顶层
RAG_SPLIT_CHOICES.md                                                          ← 本文档
```

---

## 七、回滚

如果发现重构有问题需要回滚：

1. **代码层面**：`git revert <commit-id>` 或 `git reset --hard <pre-refactor-commit>`
2. **数据库层面**：rag_* 表里的数据原本来自 chunk 表，但迁移脚本已经把原表里的数据 DELETE 了。如果代码层回滚但数据库不回滚，CHUNK 用户会看到"我的规则库丢了"。

正确回滚顺序：
```bash
# 0. 先备份 rag_* 表（迁移产物）
pg_dump -h localhost -U postgres ai_review \
    --table='rag_*' > rag_tables_backup.sql

# 1. 回滚代码
git revert <merge-commit>

# 2. 把 rag_* 数据搬回原表
# 这一步没有自动化脚本，需要写一份"反向迁移"——
# 反向脚本本质就是把 INSERT 的方向反过来。如果有需要我可以再写一份。

# 3. 删掉 rag_* 表
docker compose exec postgres psql -U postgres ai_review -c '
    DROP TABLE IF EXISTS rag_ai_call_logs, rag_review_findings,
        rag_review_pipelines, rag_document_blocks, rag_review_audit_logs,
        rag_review_tasks, rag_user_library_assignment,
        rag_scenario_library_mapping, rag_scenario_rule_mapping,
        rag_scenarios, rag_rule_check_examples, rag_rule_checks,
        rag_rules, rag_rule_libraries CASCADE;'
```

更稳的做法是直接用部署前的 `pg_dump` 全库快照恢复，不依赖反向迁移脚本。

---

如有任何决策不符合预期或要回退、调整、补做的，欢迎随时反馈。
