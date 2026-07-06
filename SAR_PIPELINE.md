# 结构化精准审查（SAR）— 方案设计与调参说明

SAR（Structure-Aware Review）是当前系统中与 **全文逐章审查（CHUNK）** 并列开放的结构化精准审查管线，物理隔离、前端可切换进入。它面向 **强结构文档 + 缺失类为主的检查单**（如 DO-160G / QTP），把传统逐章审查和历史 RAG 方案的共同短板——"定位/路由"——作为主攻点。

> 说明：RAG 相关内容在本文中作为历史方案和技术对比保留；当前前端可操作审查入口以 CHUNK / SAR 为准。

---

## 一、设计动机：攻击两条管线的共同病根「路由」

回顾 CHUNK 与 RAG 的失败模式，本质是**同一环节的两种坏法**——"把一条检查项对到文档里正确的位置"：

- **CHUNK** 由 `RuleDispatcher` 预先路由规则：`section_specific` 依赖一级章节标题命中，`test_item_chapter` 依赖“试验概述/试验项目概述”提取出的试验项目章节；标题或概述不规范时仍可能漏调度，但命中的章节会被完整通读，且当前已强制每条上传业务规则至少返回一条判定。内置 `R-Q` 文字质量/图表编号检查只在 Fail/Review 时进入矩阵；每章同时抽取 `term_observations`，汇总输出术语表并追加一次全文术语一致性审查。
- **RAG** 用**向量相似度**路由证据 → 文本不相似 / 是"缺失类"就漏；且只给碎片，缺全局上下文导致误报高。

判定（模型读原文给 Pass/Fail）这一步两者都不弱，弱的是"喂给模型的东西对不对、全不全"。SAR 因此**换掉路由层**，并利用这批文档的强结构资产（章节树、章节号、表格、`applies_to.sections/keywords`、`check_type`）。

> 一句话：**用「结构 + 词法 + 语义」三路定位每个检查项的预期区域，喂整段区域（非碎片、非整章）判定；缺失类按"预期位置清单"判 Fail；只对不确定项自适应复核；最后做跨章一致性。**

---

## 二、整体流程（`SarReviewService`）

```
解析 + 切块 + embedding 向量化（pgvector，复用 RAG 基础设施）
        |
buildSectionIndex：按 section_path/章节把块聚合成"区域"，组内按阅读顺序排序
        |
阶段1 routeCheck（并行，不耗 chat token）：三路打分定位每个检查项的预期区域，取整段区域为证据 + 路由置信度
        |
planCallBins：同区域检查项装箱（≤ max-checks-per-call），共享该区域整段证据
        |
阶段2 reviewGroup/callGroup：一个区域一次模型调用，区域级取证 + 清单式缺失判定（SAR_GROUP_SYSTEM_PROMPT）
        |
失败项补审（failed-check-retry-attempts）
        |
阶段3 自适应复核（verify.enabled & verify.adaptive）：只复核低置信/定位不确定的非 Pass 项
        |
阶段4 runConsistencyPass：跨章一致性核对 → 追加 CONSISTENCY 行
        |
一违规一行写入 allCheckResults -> 完成
```

并发由 per-task `Semaphore(check-concurrency)` 约束，提交到独立线程池 `sarCheckExecutor`（`AsyncConfig`）。

---

## 三、五大组件详解（每个都说明如何同时压漏报/误报）

### ① 三路并联路由（`routeCheck`）—— 治 CHUNK 标题漏 + RAG 语义漏
对每个检查项，对各"区域"用三路打分取并集：
- **结构**：`applies_to.sections` 命中章节号（`sectionMatches`，宽松匹配 `"5"` → `5 …` / `第5章` / `5.x`）+3；`applies_to.keywords` 命中 `section_path` +2。
- **词法**：`检查问题 + 通过标准` 抽 ASCII 词(≥3) + 中文 2-gram（`lexicalTerms`），在区域文本里命中计分（BM25 近似）。
- **语义**：向量召回 `recall-top-k`，按召回块落点累计（召回分加权）。

取得分最高区域；**路由置信度** `routeConfidence`：结构命中→0.9；best ≥ 2×次优→0.7；best > 次优→0.5；否则 0.3。
→ **降漏报**：消除单一路由盲区（标题不匹配、语义不相似都能被另一路兜住）。

### ② 区域级取证 —— 治 RAG 碎片误报
命中区域的**整段块**（阅读顺序、封顶 `region-max-blocks`）作为证据喂模型，介于"RAG 碎片"和"CHUNK 整章"之间。
→ **降误报**：上下文足够（接近 CHUNK 精度），又聚焦、省 token；表格作为整体进入也修掉表格类问题。

### ③ 清单式缺失检测 —— 针对预期位置的缺失判断
对 `check_type=presence` / `evidence_required` 的检查项，系统提示明确「**本区域=预期位置，区域内不存在满足通过标准的内容即判 Fail**」。
→ **直接消灭 RAG 的缺失盲区**（检索对"不存在的东西"无能为力），并把 CHUNK 中“规则必须先命中章节”的压力转移为“检查项先定位预期区域”。

### ④ 自适应复核（`runVerifyPass` + `isUncertain`）—— 精准治误报、不烧全量预算
一次判定给 `status + confidence`；路由置信度 < `route-confidence-threshold` 时把该项 confidence 降级为 `needs_review`。复核**只挑** `confidence ∈ {low, needs_review}` 的非 Pass 项（`verify.adaptive=true`），给 `CONFIRMED/UNCERTAIN` 标签，**只标注不删除、不改判 Pass**。
→ **降误报**：把复核预算花在刀刃上，避免 RAG"全量复核翻倍成本/碎片复核误伤"的两难。默认 `verify.enabled=false`。

### ⑤ 跨章一致性（`runConsistencyPass`）—— 两条现有管线的共同空白
把各章节标题 + 截断正文拼成摘要，让模型只找**跨章节**矛盾（温度范围/鉴定类别/设备型号/合格判据数值取值不一致、图号/表号/术语前后矛盾），产出 `check_code=CONSISTENCY`、`category=逻辑一致性` 的违规行。
→ **补两条管线都抓不到的一类漏报**（CHUNK 按章割裂、RAG 按点取证都发现不了"第3章说 A、第7章说 B"）。

---

## 四、可调参数（`application.yml → review.sar`）

```yaml
review:
  sar:
    check-concurrency: 4              # 单任务并发（路由/分组调用/复核共用），受模型 RPM 限制
    failed-check-retry-attempts: 1    # 失败项补审次数
    block-max-chars: 1800             # 单个原文分块字符上限
    embedding-batch-size: 24          # 向量化批大小
    recall-top-k: 30                  # 语义路由的向量召回候选数
    evidence-max-blocks: 10           # 三路皆未命中时的回退取证块数
    max-checks-per-call: 8            # 同区域检查项分组的箱容量
    max-evidence-per-call: 16         # 单次分组调用的证据块上限
    region-max-blocks: 14             # 区域级取证：命中区域送入模型的整段块数上限
    route-confidence-threshold: 0.45  # 低于此路由置信度 → 降级 needs_review、优先复核
    verify:
      enabled: false                  # 自适应复核总开关（开启≈每条不确定违规多一次调用）
      adaptive: true                  # 仅复核低置信/定位不确定项（而非全量）
    consistency:
      enabled: true                   # 跨章一致性核对
    vector-index:
      enabled: true
      hnsw-ef-search: 100
      binary-candidate-multiplier: 4
```

**怎么按需求拨：**
- **更不漏报** → 调大 `recall-top-k`、`region-max-blocks`（让区域取证更全）。
- **更准的缺失检测** → 把规则的 `check_type` 标成 `presence`、`applies_to.sections/keywords` 写全（路由更准 → 区域更对 → 缺失判定更可信）。
- **嫌慢/嫌贵** → 调大 `max-checks-per-call`（一次评更多项）、保持 `verify.enabled=false`、关 `consistency.enabled`。
- **要精度标签** → 开 `verify.enabled`，前端/导出按 `verifyStatus=CONFIRMED` 过滤。

> embedding / reranker 模型由后端自动取"第一个 enabled 的"；chat 模型由用户在新建审查时选。未启用 embedding 模型时，前端在 SAR Tab 会拦截提交。

---

## 五、结果字段（`sar_review_tasks.ai_result`）

与 RAG 同形：`allCheckResults`（一违规一行，每行 `finding_id` / `violationIndex` / `violationCount` / `status` / `evidence` / `confidence` / `sourceRefs`），外加跨章一致性产生的 `check_code=CONSISTENCY` 行。`checkStatusCounts`（三级 Pass/Fail/Review）、`categoryCounts`、`confidenceCounts`、`originalSources`、`chunkResults`（按检查项粒度，供溯源/导出）一应俱全。判定**只有三级、无 severity**。

`retrievalStats` 在通用字段（engine/indexStrategy/blockCount/checkCount/recallTopK/...）之外，另含 SAR 专有：`regionMaxBlocks`、`verifyEnabled`、`verifyAdaptive`、`verifiedFindings`、`confirmedFindings`、`consistencyEnabled`、`consistencyFindings`。

---

## 六、与 RAG / CHUNK 的对比（error profile）

| 维度 | CHUNK 全文逐章 | RAG 智能召回 | SAR 结构化精准 |
|---|---|---|---|
| 定位方式 | 章节标题命中 | 向量相似度 | 结构 + 词法 + 语义 三路 |
| 模型看到 | 整章原文 | top-N 碎片 | 命中区域整段 |
| 漏报来源 | 规则没调度到该章 | 召回盲区 / 缺失类 | 显著降低（三路 + 清单缺失）|
| 误报 | 较低 | 召回优先，高 | 区域上下文 + 自适应复核，居中偏低 |
| 跨章一致性 | 弱 | 弱 | 专门一层覆盖 |
| 适用 | 通用文字质量 | 通用逐项取证 | 强结构 + 缺失类检查单 |

SAR 不是"RAG 的替代"，而是针对**强结构 + 缺失类**场景把漏报/误报同时往下压；前提是文档可结构化解析、规则的 `applies_to`/`check_type` 配齐。这两者不好时，路由会退化（无命中回退向量 top-N），效果趋近 RAG。

---

## 七、相关代码与文件

**后端**
- `service/SarReviewService.java` — 全流程：`prepareDocumentVectors` → `runCheckPass`（`buildSectionIndex` → `routeCheck` 三路路由 → `planCallBins` → `reviewGroup`/`callGroup`）→ `buildRowsFromResult` → `runVerifyPass`（`isUncertain` 自适应）→ `runConsistencyPass`/`buildConsistencyRow`；提示常量 `SAR_GROUP_SYSTEM_PROMPT`。
- `service/SarRuleService` / `SarRuleLibraryService` / `SarScenarioService`、`repository/SarDocumentVectorRepository`（pgvector → `sar_document_blocks`）、`service/ChecklistRuleImportService`（`mode="SAR"` 写 `sar_*`）。
- `controller/SarReviewController` / `SarRuleController`(含 `/import-checklist`) / `SarScenarioController` / `SarRuleLibraryController`，路由 `/api/v1/sar/...`；跨管线接入 `UnifiedReviewController`。
- `config/AsyncConfig`（`sarCheckExecutor`）、`resources/application.yml`（`review.sar.*`）、`resources/schema.sql`（14 张 `sar_*` 表）。
- 输出 schema 复用 `ReviewResultSchema.ragGroupSchema()` / `ragVerifySchema()`（形状一致）。

**前端**
- `api/sarScenarios.ts` / `sarRules.ts` / `sarReviews.ts`；`api/pipelineApi.ts` 按 `mode='SAR'` 派发；`api/reviews.ts` 的 `ReviewMode` 含 `'SAR'`。
- `components/AppLayout`（第三侧边栏组）、`App.tsx`（`/sar/scenarios`、`/sar/rules`）、`DashboardPage`（筛选器/新建 Tab/入口卡）、`UserManagementPage`（库分配 Radio）。

---

## 八、验证步骤

```bash
docker compose up -d --build      # 跑 schema.sql 建 sar_* 表并编译
# 用 admin_root 登录，左侧应出现第三组「结构化精准审查」
# 1) 在该管线下：建规则库 → 上传规则 / 导入 Excel 检查单（/sar/rules/import-checklist）→ 建场景
# 2) 工作台「新建审查」第三个 Tab 选「结构化精准审查」→ 上传 Word → 提交
# 3) 详情页应见：检查项判定矩阵（含 CONSISTENCY 跨章一致性行）
# 4) ai_result.retrievalStats 含 regionMaxBlocks / consistencyFindings 等 SAR 专有字段
```

调参与对比测试可参照 [RAG_RECALL_TUNING.md](RAG_RECALL_TUNING.md) 的方法，在一篇已知"应有 N 个问题"的文档上分别跑 CHUNK / SAR，统计命中/漏/误；如需复盘历史 RAG 效果，可在保留 RAG 入口的历史环境中单独对比。
