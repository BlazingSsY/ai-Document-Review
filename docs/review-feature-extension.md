# 审查功能扩展约定

## 目标

业务功能（环境试验大纲、试验报告、试航报告等）与执行算法（CHUNK、SAR）是两个独立维度。执行算法只负责任务调度、规则调用和结果汇总；文件类型、文档结构和切片语义由各业务功能自己负责。

当前结构：

```text
review/feature/
  ReviewFeature.java                 # 功能元数据与注册契约
  ReviewFeatureRegistry.java         # Spring 自动发现，不维护中心 switch
  ReviewDocumentProcessor.java       # 文档处理扩展接口
  ReviewDocument.java                # 解析后的双视图文档
  ChapterReviewPlan.java             # CHUNK 消费的切片计划
  envoutline/
    EnvironmentTestOutlineFeature.java
    EnvironmentTestOutlineDocumentProcessor.java
```

## 新增一种审查功能

以“试验报告审查”为例，只需在独立包 `review/feature/testreport/` 中增加：

1. 一个 `ReviewDocumentProcessor` 实现，负责该类型支持的文件格式、解析方式、需要进入 SAR 的章节、CHUNK 切片方法和业务章节识别。
2. 一个 `ReviewFeature` Spring Bean，声明唯一 `category`、功能码、显示信息并返回上述处理器。
3. 该模块自己的单元测试、规则/场景实现（如果它不复用现有规则库）。

无需修改：

- `ReviewService`（CHUNK）；
- `SarReviewService`；
- `ReviewFeatureRegistry`；
- `FeaturePermissionService` 的类别分支；
- 中心类别枚举或 `if/else` 分发文件（已取消）。

注册表在启动时检查类别重复、默认功能缺失和多个默认功能等配置错误。提交任务时会按类别取得对应处理器并校验该功能的权限；重审、失败项重试和原文溯源也会按任务保存的类别重新取得同一处理器，保证各入口使用一致的文档处理方法。

## 边界约定

- `document/WordParser`、`ChunkUtils` 等只提供无业务含义的底层能力，不判断“试验项目”“报告结论”等领域概念。
- 领域关键词、章节边界、前置页/附录取舍放在各功能包内。
- 新功能若使用独立规则库，应保持 `usesSharedRuleLibraries() == false`；只有显式返回 `true` 的功能才参与现有规则库授权校验。
- 数据库 `review_category` 保存模块的 `category`。现有空值任务由默认模块兼容读取。
