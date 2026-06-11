# 机载文档审核方案改造计划

## 1. 改造目标

当前系统已经具备规则库、审查场景、Word 解析、章节切片、批量调用大模型、WebSocket 进度和 Excel 导出能力。机载文档审核项目要求在此基础上升级为：

- Excel 检查单规则化；
- QTP Word/PDF 文档结构化；
- 章节映射与混合检索取证；
- 基于证据约束的五级判定；
- 人工复核、审计日志和可交付报告闭环。

目标系统定位为“规则驱动的证据化智能审查辅助系统”，不是通用问答，也不是完全替代专家自动判定。

## 2. 目标审查链路

1. 检查单导入
   - 从 Excel 检查单读取检查项、确认目标、备注和适用章节。
   - 将自然语言检查项拆解为可执行的原子检查项。
   - 生成标准 Rule JSON，并落入规则库。

2. 规则标准化
   - 一条业务规则可以包含多个原子检查项。
   - 原子检查项记录 check_code、question、pass_criteria、fail_severity、category、evidence_required。
   - 适用范围通过 rule_type、sections、keywords 驱动规则分发。

3. 文档结构化
   - 解析标题树、章节路径、段落、表格、图题、页码和可定位 ID。
   - 形成可检索、可定位、可溯源的文档块库。

4. 证据检索
   - 对每个原子检查项执行章节映射、标题匹配、关键词检索、BM25、向量检索和 rerank。
   - 输出 Evidence JSON，包含原文片段、章节路径、block_id、table_id、page、char span。

5. 证据约束判定
   - LLM 只能基于检索到的证据判断。
   - 输出 Pass、Partial、Fail、N/A、Review。
   - 找不到有效证据时不得凭常识判 Pass。

6. 人工复核闭环
   - 审查员按检查项查看规则要求、系统判定、证据、缺失项和建议。
   - 支持确认、改判、驳回、备注。
   - 保存审计日志，导出检查结果 Excel、审查报告 Word/PDF 和审计 JSON。

## 3. 分阶段实施计划

### 阶段 1：原子规则底座

目标：让系统不再只把一个 Markdown 文件当作一条粗粒度规则，而是支持 Rule JSON 中的原子检查项。

任务：

- 增加 `RuleCheck` 实体与 Mapper，接入已有 `rule_checks` 表。
- 扩展规则上传逻辑，支持标准 Rule JSON 包。
- 上传规则包时同时创建 `rules` 和 `rule_checks`。
- 在规则详情接口中逐步暴露检查项列表。

验收：

- 一个 JSON 文件可导入多条规则；
- 每条规则下可导入多个原子检查项；
- 数据库中 `rules` 与 `rule_checks` 关系正确。

### 阶段 2：检查单导入器

目标：将 QTP Excel 检查单自动转换为 Rule JSON。

任务：

- 新增 Excel 检查单解析服务；
- 识别检查项、确认目标、检查内容、备注、章节号；
- 对复合检查项进行初步原子化；
- 生成可预览、可编辑的 Rule JSON；
- 支持管理员确认后入库。

验收：

- DO160G 第4、5、6章检查单可自动转换；
- 复杂检查项至少拆到可单独判定的粒度；
- 转换结果能被阶段 1 上传逻辑直接入库。

当前实现状态：

- 已新增后端接口 `POST /api/v1/rules/import-checklist`。
- 请求格式为 `multipart/form-data`：
  - `file`: `.xlsx` 或 `.xls` 检查单；
  - `libraryId`: 可选，导入到指定规则库。
- 接口权限：`SUPERVISOR` / `ADMIN`。
- 接口会读取 Excel、生成标准 Rule JSON，并复用规则上传链路写入 `rules` 和 `rule_checks`。
- 返回内容包含源文件名、生成的规则文件名、规则编码、规则数量、原子检查项数量、生成的 canonical JSON 和已入库规则。

第一版 Excel 解析边界：

- 支持当前 QTP 检查单常见结构；
- 支持合并单元格读取；
- 支持“检查项/确认目标”类基础信息行；
- 支持“试验项目/检查内容/检查结果/备注”类试验实施行；
- 从文件名推断 DO160G 章节号，例如 `DO160G-5章QTP检查单.xlsx` 推断为 `sections=["5"]`、`rule_code=DO160G-5-QTP`；
- 复杂自然语言原子化目前采用规则化拆分，后续应增加专家可编辑的预览确认界面。

标准 Rule JSON 示例：

```json
{
  "version": "1.0",
  "source_type": "qtp_excel_checklist",
  "source_file": "DO160G-5章QTP检查单.xlsx",
  "rules": [
    {
      "rule_code": "DO160G-5-QTP",
      "name": "DO160G 第5章 QTP评估检查",
      "rule_type": "section_specific",
      "document_type": "QTP",
      "applies_to": {
        "sections": ["5"],
        "keywords": ["QTP", "DO160G", "DO-160G", "温度变化"]
      },
      "checks": [
        {
          "check_code": "DO160G-5-QTP-001",
          "check_type": "presence",
          "question": "是否满足检查项“鉴定试验类别”：与设备技术规范（或技术要求）一致。",
          "pass_criteria": "QTP中应提供能够证明该检查项满足要求的明确内容。",
          "fail_severity": "high",
          "category": "标准符合性",
          "evidence_required": true,
          "display_order": 1
        }
      ]
    }
  ]
}
```

### 阶段 3：文档结构化库

目标：从当前章节切片升级为可定位的结构化文档块。

任务：

- 新增 `documents`、`document_sections`、`document_blocks`、`document_tables`。
- Word 解析时保存标题层级、章节路径、段落块、表格块、图题占位符。
- 为每个块生成稳定 `block_id`。
- 保留 chunk 切分结果与 block 的映射。

验收：

- 任意审查结果可以追溯到 block；
- 表格内容可按单元格或行作为证据参与检索；
- 前端可按 block_id 定位原文。

### 阶段 4：混合检索与证据对象

目标：让每个检查项先取证，再判定。

任务：

- 新增 `EvidenceRetrievalService`。
- 实现章节映射、关键词检索和 BM25 作为第一版检索。
- 预留向量检索与 rerank 接口。
- 输出 Evidence JSON。
- 增加证据不足、模板占位符、证据冲突的识别规则。

验收：

- 每个检查项至少返回 top-k 候选证据；
- 自动判定输入中包含证据，不再直接输入整章全文；
- 可统计证据召回率。

### 阶段 5：五级判定引擎

目标：将输出从问题列表升级为检查项判定矩阵。

任务：

- 新增 `CheckResultSchema`。
- 输出 `check_results[]`，状态为 Pass、Partial、Fail、N/A、Review。
- 支持 missing_items、evidence、reason、suggestion、confidence。
- 高风险或低置信度结果进入人工复核重点列表。

验收：

- 每个 active rule_check 都有对应判定；
- 未找到证据的检查项不会被判 Pass；
- 结果可按原检查单顺序导出。

### 阶段 6：复核、审计与报告

目标：形成可交付业务闭环。

任务：

- 前端新增检查项矩阵视图；
- 每条审查结果新增通过、不通过、备注等label，并在单条审查结果中支持人工确认、修改等操作；
- 新增审计日志表；
- 导出检查结果 Excel、审查报告 Word/PDF、审计 JSON；
- 支持证据高亮页面。

验收：

- 人工改判可追踪；
- 导出文件包含系统判定和人工最终判定；
- 审计日志可还原审查过程。

## 4. 当前第一步实施范围

本次先实施阶段 1 的后端底座：

- 增加 `RuleCheck` 实体；
- 增加 `RuleCheckMapper`；
- 扩展规则上传解析，使标准 Rule JSON 可以将 `checks[]` 入库。

后续再接入审查执行流程，使 `ReviewService` 由“按规则 prompt 审查”逐步转为“按原子检查项取证和判定”。

## 5. 阶段 3 过渡实现记录

本次先完成审查结果与原文对照能力，为后续 `block_id` 级证据定位打基础：

- `ReviewService` 在每个 `chunkResults[]` 中增加 `source` 对象，包含 `blockId`、`chunk`、`sectionPath`、`text`、`textLength`、`estimatedTokens`；
- 聚合后的 `allIssues[]` 增加 `sourceChunk` 和 `sourceTitle`，用于前端稳定关联对应原文；
- 前端审查结果页改为左侧显示大模型审查结果，右侧显示当前选中问题对应的原文片段；
- 审查概要移动到页面顶部，审查日志和运行信息移动到底部，右侧区域只承担原文阅读和定位线索展示；
- 旧任务如果没有 `source` 字段，前端会尝试按章节名、位置文本做兼容匹配，并提示需要重新审查以获得完整原文。

后续阶段 3 的正式实现仍需补齐 `documents`、`document_sections`、`document_blocks` 等结构化持久化表，并把当前临时 `CHUNK-xxx` 升级为稳定的文档块 `block_id`。

## 6. 阶段 4 模型配置底座

本次先完成混合检索所需的模型配置入口：

- `ai_model_config` 增加 `model_type`，取值为 `chat`、`embedding`、`reranker`；
- `ai_model_config` 增加 `embedding_dimension`，用于记录向量模型维度；
- `/api/v1/models/enabled` 支持按 `modelType` 查询启用模型，默认返回 `chat`，避免向量模型或重排模型被误选为审查大模型；
- 模型连接测试按类型分派：
  - `chat` 调用 `/chat/completions`；
  - `embedding` 调用 `/embeddings` 并校验返回向量维度；
  - `reranker` 调用 `/rerank` 并校验返回重排结果；
- 前端模型管理页面新增“模型用途”，并按用途显示对应字段：
  - 审查大模型：最大 Token、Temperature、思考模式；
  - Embedding：向量维度；
  - Reranker：连接参数和超时。

后续第四阶段继续实现 `EvidenceRetrievalService`，按规则检查项从 `embedding` 模型生成向量召回候选证据，再用 `reranker` 模型对证据候选排序。

## 7. 阶段 5 五级判定引擎

本次完成检查项判定矩阵的第一版闭环：

- 审查输出 Schema 增加 `check_results[]`，每条检查项输出：
  - `check_code`、`rule_code`、`check_question`；
  - `status`：`Pass`、`Partial`、`Fail`、`N/A`、`Review`；
  - `reason`、`evidence`、`missing_items`、`suggestion`、`confidence`；
- `ReviewService` 在加载规则后读取 `rule_checks`，把原子检查项注入到对应规则 prompt；
- 批量审查和单切片审查共用同一套五级判定 Schema；
- 多采样合并时按检查项编号合并 `check_results`，状态采用保守策略：`Fail > Partial > Review > N/A > Pass`；
- 聚合结果增加：
  - `allCheckResults`；
  - `totalCheckResults`；
  - `checkStatusCounts`；
- 前端审查结果页优先展示“检查项判定矩阵”，旧任务没有 `allCheckResults` 时继续展示原问题列表；
- Excel 导出优先导出检查项判定矩阵，旧任务继续导出审查意见表。

该阶段仍保留旧的 `issues[]/passed_items[]`，用于兼容历史结果和问题列表视图。后续阶段可在人工复核模块中基于 `check_results[]` 增加人工确认、改判和审计日志。

## 8. 阶段 6 复核、审计与报告

本次完成审查闭环：

- 新增 `review_audit_logs`，记录人工复核动作、复核前后 JSON、备注和操作者；
- 新增 `PUT /api/v1/reviews/tasks/{taskId}/check-decisions`，支持对单个检查项写入：
  - `manualStatus`；
  - `manualAccepted`；
  - `manualComment`；
  - `manualReviewerId`；
  - `manualReviewedAt`；
- 人工复核结果同步写回 `allCheckResults` 和对应 chunk 内的 `check_results`；
- 聚合结果增加 `manualReviewSummary`，统计已复核、待复核和人工最终判定分布；
- 新增审计日志查询与导出：
  - `GET /api/v1/reviews/tasks/{taskId}/audit`；
  - `GET /api/v1/reviews/tasks/{taskId}/audit/export`；
- 新增 Word 审查报告导出：
  - `GET /api/v1/reviews/tasks/{taskId}/report`；
- 前端结果页支持在检查项判定矩阵中选择检查项并进行人工复核；
- 前端提供 Excel、Word 报告、审计 JSON 三类导出。

当前第六阶段的报告导出为 Word `.docx` 第一版，内容包括任务信息、概要、检查项矩阵和审计日志。后续如需 PDF，可在服务端增加 Word 转 PDF 或 HTML 打印导出链路。

## 9. pgvector 数据库向量检索

本次将 RAG 召回从 Java 内存余弦计算升级为 PostgreSQL `pgvector`：

- PostgreSQL 镜像改为 `pgvector/pgvector:0.8.2-pg16`，数据库启动时执行 `CREATE EXTENSION vector`；
- `document_blocks` 增加原生 `embedding vector` 列，旧版 `embedding_vector TEXT` 数据会在启动迁移时转入新列；
- 文档块向量使用 JDBC 批量写入，避免逐条 MyBatis 插入；
- 召回查询在数据库内使用余弦距离完成，不再把任务的全部向量加载到 Java 后反序列化和排序；
- 按 Embedding 模型和实际维度创建部分 HNSW 索引，避免相同维度但不同模型的向量混入同一索引；
- 维度不超过 2000 时使用 `vector` HNSW；
- 维度为 2001 到 4000 时使用 `halfvec` HNSW；
- 维度高于 4000、且不超过 pgvector 上限 16000 时，使用 binary quantization HNSW 召回候选，再按原始向量余弦距离重排；
- 查询开启 `hnsw.iterative_scan=strict_order`，改善按任务过滤时的召回数量；
- 结果中的 `retrievalStats` 会记录 `engine=pgvector`、索引策略、向量维度和 HNSW 搜索参数。
