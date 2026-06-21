# RAG 召回审查 — 实现逻辑与调参说明

> **更新说明（已对齐当前代码）**：本文档此前描述的是"每检查项一次调用 + 证据分批"的早期实现。
> 现在代码已演进为 **按章节分组、一次调用评估多个检查项** 的"分组评估"架构以降低 token，
> 默认参数与开关也有变化（如 `verify.enabled` 默认改为 `false`）。下方流程、参数、代码引用
> 均以 `RagReviewService` / `application.yml` 现状为准。

核心策略不变：**召回优先（先不漏报，再治误报）**，用于解决"RAG 审查发现的问题太少"。

---

## 一、整体流程（`executeReviewAsync` → `executeReview`）

四个阶段，全部在 `service/RagReviewService.java`：

### 阶段 0：文档解析与向量化（`prepareDocumentVectors`）
- `WordParser.parseChapters` 解析 Word；`ChunkUtils.findFirstRealChapterIndex` 跳过封面/目录等非正文章节。
- `buildBlocks` / `splitChapterNodes` 按文档节点切块：单块字符上限 `block-max-chars`（1800），超长节点二次切分；每块带 `blockId / sectionPath / chapterIndex / startNodeId / endNodeId`，便于回链原文。
- `embedBlocks` 用 embedding 模型批量向量化（`embedding-batch-size` 24），写入 pgvector；`vector-index.enabled=true` 时建/复用 HNSW 索引（`hnsw-ef-search` 100、`binary-candidate-multiplier` 4）。

### 阶段 1：逐检查项检索证据（`runCheckPass` 第一步，**不耗 chat token**）
- `buildCheckPlans` 从场景规则展开检查项：每条 active `RagRuleCheck` = 一个 `CheckPlan`（check_code / 检查问题 / 通过标准 / 分类）。
- 每个检查项**并行**检索（per-task 信号量，并发上限 = `check-concurrency` 4）：
  - 检索 query = 规则名 + 规则码 + check_code + 检查问题 + 通过标准 + 规则正文（`buildRetrievalQuery`）。
  - `recall`：pgvector 取 `recall-top-k`（30）个候选块。
  - `rerank`：若配了 reranker 模型，精排后取 `evidence-max-blocks`（10）块作为该检查项证据；无 reranker 则按向量序截断到同样数量。
  - 检索失败不致命 → 该项以空证据继续（模型按"无证据"判定）。

### 阶段 2：按章节分组评估（**核心降本改动**）
相比早期"每检查项一次调用"，现在把检查项合并到更少的模型调用里：
- `planCallBins`：按**首要证据块所在章节 / `sectionPath`** 把检查项分组，组内再按 `max-checks-per-call`（8）装箱。
- 每个箱的共享证据 = 箱内成员证据并集，按 `blockId` 去重保留高分、按召回分降序、封顶 `max-evidence-per-call`（16）（`unionEvidence`）。
- `reviewGroup` → `callGroup`：**一个箱 = 一次 chat 调用**，同一段原文只发一遍、一次评估多个检查项。
  - 系统提示 `RAG_GROUP_SYSTEM_PROMPT`（召回优先）：三级判定 **Fail / Review / Pass**；**要求内容在证据中缺失 = Fail**（不放过）；每处违规各列一条不合并；仅"证据自相矛盾/确实无法判断"才用 Review。
  - 调用参数：`temperature=0`、`top_p=1`、`max_tokens=8192`、稳定 seed、开 prompt cache、强制 JSON Schema（`ragGroupSchema` / `rag_group_result`）。
- 回填（`buildRowsFromResult`）：按 check_code 取回每项结果；每处违规展开成 `allCheckResults` 的**一行**（`finding_id = checkCode#idx`，带 `violationIndex/violationCount`），跨结果按 归一化(location+evidence) 去重；Pass 检查产 1 行 Pass；模型漏返回的 check_code → 标失败，交补审轮兜底。

### 阶段 1.5：失败项补审
- `failed-check-retry-attempts`（1）：把首轮失败的检查项重新跑一遍 `runCheckPass`，结果覆盖回填；仍失败 → `buildFailedCheck` 兜底为一行 Review，提示人工复核。

### 阶段 3：两阶段复核（默认**关闭**）
- `verify.enabled` 默认 **`false`**（为控成本；早期默认开）。
- 开启后对**每条非 Pass 违规**各发一次独立复核（`runVerifyPass` 并发执行），产 `verifyStatus ∈ {CONFIRMED, UNCERTAIN}` + `verifyReason` 写回该行，schema 为 `ragVerifySchema` / `rag_verify_result`。
- **铁律**：复核**只标注，绝不删除发现、绝不把 Fail 改判为 Pass**；复核调用失败也保留发现并标 `UNCERTAIN`。

---

## 二、召回优先的根因改造（仍然有效）

### A：一个检查项 → 多条违规（打破"发现数 = 检查项数"硬上限）
schema 的 `findings[]` 让单个检查项可报多处违规；`buildRowsFromResult` 把每处展开成 `allCheckResults` 一行（`finding_id` / `violationIndex` / `violationCount`），跨结果按 归一化(location+evidence) 去重。发现数不再被检查项数量卡死。

### B：扩大证据窗口（解决早期 top-6 漏看违规）
`recall-top-k` 召回候选、rerank 后取 `evidence-max-blocks` 作为该检查项证据；相比早期截断到 6 块，覆盖更多违规位置。进入分组后，证据还会按 `max-evidence-per-call` 做并集封顶。

### C：召回优先 Prompt（`RAG_GROUP_SYSTEM_PROMPT`）
"尽可能找全、宁多勿漏；要求内容在证据中缺失 = Fail；同类问题多处各列一条；仅证据矛盾/确实无法判断才用 Review。"

### G：两阶段复核只标注不删除
高召回必然带来误报；复核给出 `CONFIRMED/UNCERTAIN` 精度信号，但不牺牲召回。默认关闭，需要精度标注时再开。

---

## 三、可调参数（`application.yml → review.rag`）

```yaml
review:
  rag:
    check-concurrency: 4           # 单任务并发（检索/分组调用/复核共用），受模型 RPM 限制
    failed-check-retry-attempts: 1 # 首轮失败项的补审次数
    block-max-chars: 1800          # 单个原文分块字符上限
    embedding-batch-size: 24       # 向量化批大小
    recall-top-k: 30               # 每检查项 pgvector 召回候选数（不耗 chat token，可适当大）
    evidence-max-blocks: 10        # 每检查项 rerank 后保留、送入分组评估的证据数（实际作为 rerank 的 topN）
    max-checks-per-call: 8         # 一次分组调用最多评估多少检查项（越大越省 token，受 max_tokens 约束）
    max-evidence-per-call: 16      # 一次分组调用共享证据块上限（去重后按召回分取前 N）
    verify:
      enabled: false               # 两阶段复核（开启 ≈ 每条非 Pass 违规多一次 AI 调用）
    vector-index:
      enabled: true
      hnsw-ef-search: 100
      binary-candidate-multiplier: 4
```

**怎么按需求拨：**
- **还想更不漏报** → 调大 `recall-top-k`（如 60）、`evidence-max-blocks`（如 16~24）、`max-evidence-per-call`（如 24）。
- **嫌慢/嫌贵** → 调大 `max-checks-per-call`（一次评更多项 → 调用更少）、保持 `verify.enabled=false`、调小 `evidence-max-blocks`。
- **以后要压误报** → 打开 `verify.enabled`，前端/导出按 `verifyStatus=CONFIRMED` 过滤即可（数据已备好，无需重跑）。

> 实际证据量由 `evidence-max-blocks`（每检查项 rerank 上限）和 `max-evidence-per-call`（每次分组调用的去重证据上限）决定。早期的 `rerank-top-n` / `evidence-batch-size` 两个旧旋钮已随分组评估改造删除。

---

## 四、成本与时延

- 调用次数主体 = **分组调用数**，约 = ⌈检查项数 / `max-checks-per-call`⌉，再因"按命中章节分组"而略有放大（只有同章节的检查项才会合并到一次调用）。相比早期"每检查项一次调用、原文重发 N 遍"，省 token 的关键就在这里。
- 失败项补审：仅对首轮失败项重跑，通常数量很少。
- 复核（若开启）：每条非 Pass 违规 1 次额外调用——token 大户，故默认关闭。
- 所有调用走 `ragCheckExecutor`，`check-concurrency` 控单任务并发（注意模型 RPM/并发配额）。

---

## 五、关键数据字段（`ai_result`）

| 字段 | 说明 |
|---|---|
| `allCheckResults[]` | "一违规一行"；每行带 `finding_id` / `violationIndex` / `violationCount` / `status` / `evidence` / `sourceRefs` / `retrievalScores`；开复核时附 `verifyStatus` / `verifyReason` |
| `totalFindings` | 展开后的发现总行数 |
| `chunkResults[]` | 仍按检查项粒度保留（来源追溯/导出用），`result.check_results` 内含该检查的全部行 |
| `checkStatusCounts` | 各 status 计数 |
| `retrievalStats` | `engine` / `indexStrategy` / `embeddingDimension` / `blockCount` / `checkCount` / `checkConcurrency` / `recallTopK` / `hnswEfSearch` / `rerankedChecks` / `initial-retried-recovered-remainingFailedChecks` / `evidenceMaxBlocks` / `totalFindings` / `verifyEnabled` / `verifiedFindings` / `confirmedFindings` |

`problemCount`（仪表盘"发现问题"列）= 非 Pass 行数，详情页问题计数同源，均自动反映真实违规数。

---

## 六、验证步骤

```bash
docker compose up -d --build
# 跑一篇已知"应有不少问题"的文档，走「智能召回审查」：
#   1) 仪表盘"发现问题"列数字明显上升
#   2) 详情页同一检查项下出现多张违规卡（违规 1/N、2/N…）
#   3) 开启 verify 时每张卡带「复核确认 / 复核待定」标签
#   4) ai_result.retrievalStats 里 recallTopK / evidenceMaxBlocks / totalFindings 等有值
```

**校验是否"过度召回"**：开启复核后看 `verifyStatus=UNCERTAIN` 占比；占比高 = 误报偏多，是下一阶段"治误报"的输入信号（提高复核门槛 / 折叠 UNCERTAIN / 加阶段二精确定位）。

---

## 七、设计取舍（解释代码为何如此）

1. **输出"一违规一行"**而非"一检查一行 + 嵌套违规"：前端检查矩阵天然按行渲染，且每处违规可独立做人工改判/高亮定位。代价是人工改判 key 从 `check_code` 升级为 `finding_id`（向后兼容：不传则回退 `check_code`）。
2. **分组评估优先省 token**：按章节聚合、一次调用评估多检查项，是当前相对早期实现最大的差异。代价是单次输出更大（受 `max_tokens` 约束，`max-checks-per-call` 取 8 较稳妥）。
3. **复核只标注不删除**：严格贯彻"先不漏报"。如需"UNCERTAIN 直接降级隐藏"，加个开关即可。
4. **复核默认关**：召回拉高后误报变多，但复核纯属精度标注、对召回无贡献且翻倍 token，故默认关闭，确需时再开。
5. **未做阶段二（类型感知全章节扫描）**：对"完整性/一致性需扫全章节"的检查，阶段一的召回 + 分组已能覆盖大部分；个别仍漏的，按 `sections/keywords` 把命中章节全文喂入是最强补充，随时可加。

---

## 八、相关代码

**后端**
- `review/ReviewResultSchema.java` — `ragGroupSchema()`（分组评估，**当前使用**）/ `ragVerifySchema()`（复核）。早期的单检查项 `ragCheckSchema()` 已随分组评估改造删除。
- `service/RagReviewService.java` — 全流程：`prepareDocumentVectors` → `runCheckPass`（检索 → `planCallBins` 装箱 → `reviewGroup` → `callGroup`）→ `buildRowsFromResult` → `runVerifyPass`；召回优先提示常量 `RAG_GROUP_SYSTEM_PROMPT`。
- `service/ReviewExportUtil.java` — `findCheckResult` / `syncChunkCheckResult` 支持 `finding_id` 精确定位。
- `dto/ManualCheckDecisionRequest.java` — `findingId`。
- `resources/application.yml` — `review.rag.*` 召回/分组/复核参数。

**前端**
- `api/reviews.ts` — `ManualCheckDecisionParams.findingId`。
- `pages/reviewWorkspace/useReviewWorkspace.ts` — 人工改判传 `finding_id`。
- `pages/reviewWorkspace/components.tsx` — 违规序号标签（违规 i/N）+ 复核状态标签（复核确认 / 复核待定）。
