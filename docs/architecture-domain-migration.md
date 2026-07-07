# 项目架构按业务域收拢迁移说明

## 背景

本项目仍然保留前后端分离的顶层结构：

```text
backend/
frontend/
docker/
docs/
prompts/
output/
```

这层划分适合当前部署和开发方式，不做拆仓或服务拆分。本次调整的重点是：在 `backend` 和 `frontend` 内部，从单纯按技术层分目录，逐步迁移为按业务域组织代码。

原结构主要是：

```text
backend/src/main/java/com/aireview/
  controller/
  service/
  repository/
  entity/
  dto/
  util/
  review/
  config/
  websocket/

frontend/src/
  api/
  components/
  pages/
  store/
  styles/
  utils/
```

这种结构在项目早期很直观，但随着 CHUNK 审查、SAR 审查、规则库、场景、模型配置、文档解析、导出等业务域变多，横向技术分层会让同一个业务链路分散在很多目录里。比如规则相关代码会散落在 `controller`、`service`、`repository`、`entity`、`dto`、`util` 里，排查和修改一条业务链路时需要频繁跨目录跳转。

## 调整目标

本次迁移遵循以下目标：

1. 保留顶层 `backend/` 和 `frontend/`，不改变前后端分离部署形态。
2. 后端按业务域收拢 Controller、Service、Entity、Repository、DTO 等代码。
3. 前端按业务功能收拢页面、API、组件、状态和样式。
4. 不修改接口路径、不修改业务逻辑、不改变数据库结构。
5. 迁移后必须能通过后端编译、后端测试和前端构建。

## 后端新结构

后端源码调整为：

```text
backend/src/main/java/com/aireview/
  AiReviewApplication.java
  auth/
  common/
  dashboard/
  document/
  export/
  modelconfig/
  review/
  rule/
  scenario/
  user/
```

### auth

认证与安全相关代码：

```text
auth/
  controller/
  dto/
  security/
  service/
```

主要包含：

- 登录、注册相关接口与 DTO
- JWT 生成与校验
- Spring Security 配置
- 当前用户工具类 `SecurityUtils`

### common

跨业务域共享能力：

```text
common/
  config/
  dto/
  health/
  persistence/
  web/
  websocket/
```

主要包含：

- 通用响应对象：`ApiResponse`、`PageResponse`
- 异步线程池配置
- MyBatis-Plus 和 JSONB 类型处理
- 全局异常处理
- Web 配置
- 健康检查
- WebSocket 连接与任务进度广播

### dashboard

仪表盘统计相关代码：

```text
dashboard/
  controller/
  service/
```

用于首页统计、任务概览等数据聚合。

### document

文档解析和切片相关工具：

```text
document/
  WordParser.java
  ChunkUtils.java
  DocumentSourceMapper.java
  ChapterReferenceResolver.java
```

这里放与 Word 文档解析、章节切片、原文映射、章节引用解析相关的能力。

### export

审查结果导出：

```text
export/
  ReviewExportUtil.java
```

用于 Excel、Word、JSON 等审查结果导出。

### modelconfig

AI 模型配置和调用适配：

```text
modelconfig/
  controller/
  dto/
  entity/
  repository/
  service/
```

主要包含：

- 模型配置 CRUD
- 模型连通性测试
- AI 调用服务
- AI 调用参数与异常

### review

审查主域，内部继续区分 CHUNK、SAR 和公共审查内核：

```text
review/
  controller/
  core/
  dto/
  llm/
  migration/
  chunk/
    controller/
    entity/
    repository/
    service/
  sar/
    controller/
    entity/
    repository/
    service/
```

说明：

- `review/chunk`：全文逐章审查管线。
- `review/sar`：结构化精准审查管线。
- `review/controller`：跨管线统一查询接口，如统一任务列表。
- `review/core`：审查结果结构、批处理规划、模型分层等公共内核。
- `review/dto`：审查任务和人工复核相关 DTO。
- `review/llm`：LLM 请求、响应、schema 校验、JSON 提取等。
- `review/migration`：历史 prompts 迁移相关逻辑。

### rule

规则域：

```text
rule/
  controller/
  dto/
  engine/
  entity/
  repository/
  service/
```

主要包含：

- CHUNK / SAR 规则管理
- 规则库管理
- 检查单导入
- 规则解析
- 规则元数据解析
- 规则调度

其中原 `util` 下的规则相关工具已经收拢到 `rule/engine`。

### scenario

审查场景域：

```text
scenario/
  controller/
  dto/
  entity/
  repository/
  service/
```

包含 CHUNK / SAR 场景配置，以及场景和规则库的映射关系。

### user

用户与权限域：

```text
user/
  controller/
  dto/
  entity/
  repository/
  service/
```

主要包含：

- 用户信息
- 密码修改
- 用户管理
- 用户与规则库分配关系

## 后端关键配置变化

原来 MyBatis Mapper 只扫描：

```java
@MapperScan("com.aireview.repository")
```

迁移后 Mapper 分散到多个业务域，因此调整为：

```java
@MapperScan({
        "com.aireview.modelconfig.repository",
        "com.aireview.review.chunk.repository",
        "com.aireview.review.sar.repository",
        "com.aireview.rule.repository",
        "com.aireview.scenario.repository",
        "com.aireview.user.repository"
})
```

该配置位于：

```text
backend/src/main/java/com/aireview/AiReviewApplication.java
```

这是本次迁移中最关键的运行期配置变化。否则代码即使编译通过，启动时也可能找不到 Mapper Bean。

## 前端新结构

前端源码调整为：

```text
frontend/src/
  app/
  features/
  shared/
  main.tsx
  vite-env.d.ts
```

### app

应用入口和路由：

```text
app/
  App.tsx
```

`main.tsx` 仍保留在 `src` 根下，作为 Vite 入口。

### shared

跨业务功能共享代码：

```text
shared/
  api/
  components/
  styles/
  utils/
```

主要包含：

- Axios 请求封装
- 全局布局
- 路由保护组件
- 全局样式
- 通用常量
- WebSocket 工具

### features

业务功能目录：

```text
features/
  auth/
  dashboard/
  modelConfig/
  review/
  rules/
  scenarios/
  users/
```

各业务域内部按需要放置：

```text
api/
pages/
components/
store/
styles/
workspace/
```

例如审查域：

```text
features/review/
  api/
  components/
  pages/
  store/
  styles/
  workspace/
```

规则域：

```text
features/rules/
  api/
  components/
  pages/
```

这样页面、接口、组件和状态更靠近业务本身，后续修改某个功能时不用在 `api/`、`pages/`、`components/` 之间来回跳。

## 迁移原则

后续新增代码建议遵循以下原则：

1. 优先放到对应业务域下，而不是恢复旧的横向目录。
2. 跨多个业务域复用的代码放到 `common` 或 `shared`。
3. 后端公共业务内核放到 `review/core`、`rule/engine`、`document` 等明确目录。
4. 不为“整理目录”单独做无业务价值的大搬家。
5. 当修改某条链路时，可以顺手把相关旧代码继续收拢，但要保证每一步可编译、可构建。

## 验证结果

迁移完成后已执行以下验证：

```text
后端编译：
docker run --rm -v aireview-maven-cache:/root/.m2 -v "${PWD}\backend:/app" -w /app maven:3.9-eclipse-temurin-17 mvn -s settings.xml -DskipTests compile -B

后端测试：
docker run --rm -v aireview-maven-cache:/root/.m2 -v "${PWD}\backend:/app" -w /app maven:3.9-eclipse-temurin-17 mvn -s settings.xml test -B

前端构建：
npm run build
```

结果：

```text
后端编译：BUILD SUCCESS
后端测试：Tests run: 11, Failures: 0, Errors: 0, Skipped: 0
前端构建：build success
```

前端构建仍有 Vite 的 chunk 体积 warning，这是构建体积提示，不是本次目录迁移引入的错误。

## 注意事项

本次迁移主要是目录和包名调整，Git 未暂存时会显示大量删除和新增文件。暂存后 Git 通常会识别为 rename。

迁移不改变：

- API URL
- 数据库表结构
- Docker Compose 服务结构
- 前后端部署方式
- CHUNK / SAR 审查业务逻辑

后续如果继续做架构收敛，可以优先处理：

- `review/llm` 是否进一步归入 `modelconfig` 或保留为审查基础设施。
- CHUNK / SAR 中重复逻辑是否抽到 `review/core`。
- 规则导入、规则解析和规则调度是否继续拆成更清晰的子模块。
