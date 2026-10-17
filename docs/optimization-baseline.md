# 优化事实与验证基线：待办 01

日期：2026-10-17，时区 Asia/Shanghai。配套文件：[优化任务书](codex-optimization-prompt.md)。

本次只修订这两份文档；没有修复业务问题、迁移数据库、修改计费、部署服务器或调用真实收费模型。任务书中的后续要求不是已完成结果，也不自动授权执行后续待办。

## 1. 源码版本与适用范围

| 视图 | 版本 | 口径 |
|---|---|---|
| 完整开发基线 | `26df6f71ba6c2a24c969578fc6c741d9b8356d05`，提交标题 `Record verified MCP module publication and production deployment` | 本次代码评审、测试及架构门禁针对该开发工作区；没有 Java、SQL、POM 或 YAML 未提交改动 |
| 公开核心源码 | `3d935e8250dc7e91c5524841dc58b526fe790b05` | 公开 `main`，提交标题 `Publish verified core source`；不含开发测试、架构模块、Wrapper 与运维资料 |
| 本次公开交付 | 从上述公开提交建立独立文档分支 | 仅加入两份文档；不发布完整开发历史、不合并其他源码、不携带工作区已有删除 |

公开源码与开发基线不是同一源码树。对公开树中的 284 个 `src/main` 文件及模块 POM 单向检查，发现 24 个内容不同或在开发树已不存在的文件；这不是完整双向增删统计。差异包括 `ModelCallClient`、`ModelCallGroupService`、RAG 检索/持久化、Zine、旧 AgentWorkflow，以及 `ModelPrompts` 所在模块。

因此，下文测试结果只适用于完整开发工作区；不能当成公开副本的独立测试或部署验收。若后续在公开 `main` 实施，先确定要采用的源码基线，再重新定位方法、依赖与契约。

开发工作区在本次任务开始前已有 29 个已跟踪文档删除及未跟踪原任务书。删除未纳入本次提交，也未恢复；其意图在待办 10 处理。原始测试日志、临时数据和私有环境均留在忽略目录，不公开上传。

## 2. 模块与配置事实

- 完整开发树：13 个子模块，含根聚合器为 14 个 Maven reactor 项目。
- 组成：`platform-parent`；4 个共享库 `agent-api` / `agent-common` / `agent-runtime` / `agent-mcp`；7 个可部署服务；`agent-architecture-tests`。
- 公开核心源码：不含架构测试模块，因此为 12 个子模块，含根聚合器为 13 个 reactor 项目。
- 默认端口：gateway 18080、auth 18081、media 18082、content 18083、orchestrator 18084、chat 18085、rag 18086；部署环境允许覆盖。
- 完整开发树 `src/main/java`：221 个 Java 文件、28,204 行。`src/test/java` 下有 157 个 `*Test.java`；文件数不等于执行用例数，运维目录的 Java 自测另计。
- 六个业务服务的默认数据源指向 database `agent`、账号 `agent`；Flyway 历史表分别为 `flyway_schema_history_{auth,chat,rag,media,content,orchestrator}`。这里只核对源码配置，没有查询真实运行库表数或生产账号权限。
- 五个内建 Agent 工具为 `rag.search`、`rag.answer`、`rag.read_sections`、`visual_memory.search`、`media.create`。实际决策允许集合来自 `ExecutionToolRegistry.names(scope)`，经审核的 MCP 工具可按配置和租户加入。
- 源码默认 `app.mcp.enabled` 与 `spring.ai.mcp.client.enabled` 为 false；源码默认值不代表生产环境。

## 3. 两条对话入口的契约

### 3.1 旧聊天：数字事件载体

`POST /api/chat` 与别名 `POST /api/chat/completions` 返回 `text/event-stream`。HTTP 层返回 `Flux<ChatEventVO>`，JSON 载体为 `eventType`（整数）和 `eventData`；不能把枚举名、小写语义标签或内部 `GenerationEvent.type` 当成此入口的 SSE `event:` 名称。

证据类：`ChatController`、`ChatEventVO`、`ChatEventTypeEnum`、`ChatService`，均在 `agent-chat-service/src/main/java/com/bbb/exercise/agentdemo/chatservice/` 下。

| 数字 | 枚举 | 当前 eventData |
|---|---|---|
| 1001 | DATA | 文本字符串；内部 `answer_delta` 映射为此事件 |
| 1002 | STOP | 构建器不赋值，为 null；空值序列化行为必须随兼容测试保留 |
| 1004 | ERROR | 错误说明字符串 |
| 1010 | SESSION_INFO | 对象：`conversationId`、`created`、`timestamp`（毫秒） |
| 1011 | IMAGE_JOB | 对象：`jobId`、`status`、`mode`、`createdAt`、`startedAt`、`completedAt`；原任务书遗漏了此实际派发事件 |
| 1012 | SCENE_CARD | 对象：`summary`、`subjects`（列表）、`palette`、`composition`、`mood`、`sourceAssetId` |
| 1013 | WAITING_FOR_INSTRUCTION | 对象：`state`、`message`，此处 state 为 `WAITING_FOR_INSTRUCTION` |
| 1014 | GENERATION_CREATED | 枚举保留定义；当前 ChatService 未发现派发点，不凭注释虚构现行 payload |
| 1015 | ANSWER_REPLACE | 包装对象：`generationVersion`、`payload`；当前替换正文 payload 含 `text` |
| 1016 | FALLBACK_STARTED | 包装对象：`generationVersion`、`payload`，payload 为下述模型状态字段 |
| 1017 | MODEL_STATUS | 包装对象：`generationVersion`、`payload`，payload 为下述模型状态字段 |

`ModelCallClient.statusPayload()` 的模型状态字段为 `provider`、`model`、`capability`、`state`、`fallback`、`role`、`message`。这里记录当前构建点，不允许泄露凭据或内部地址。

内部 `answer_committed` 用于确认最终文本和历史保存，旧聊天的文本流过滤器不把它直接作为新的对外事件。正文替换不能被错误地累加为增量。会话事件、图片任务、错误收尾、备用回答替换和停止行为都应纳入待办 08 的序列回归。

### 3.2 持久执行：状态快照与命名 SSE

`POST /api/agent-executions` 创建任务并返回 HTTP 202 的 `ExecutionStore.View`，不是旧聊天的直接文本流。

`GET /api/agent-executions/{id}/events` 返回命名 SSE：

- `event` 来自持久事件的 `type`。
- `id` 为 `<executionId>:<seq>`。
- `data` 为 `ExecutionStore.Event`：`executionId`、`seq`、`type`、`generationVersion`、`payload`。
- 支持 `Last-Event-ID` 和 `after` 游标；事件查询先验证身份、归属与游标。另有 heartbeat 注释，不可转换成业务事件。
- 状态查询、停止和恢复为独立端点；停止已派发调用不等于取消供应商消费。

`ExecutionStore.View` 当前字段：`executionId`、`requestId`、`type`、`status`、`version`、`lastSeq`、`question`、`answer`、`sources`、`error`、`roundsUsed`、`roundLimit`、`tokensUsed`、`toolCalls`、`generationVersion`、`reservedTokens`、`modelStatus`。

兼容要求是分别保持两条入口的现有字段、类型、空值和顺序/回放语义，不是强行把它们改为同一种协议。待办 05 先分析，待办 08 补完整序列与边界样本；本次没有变更或宣称已穷尽测试所有 payload 变体。

## 4. Java 验证结果与统计口径

编译在本次 01 中重新执行，2026-10-17 14:41:45 +08:00 结束：`BUILD SUCCESS`，14 个 reactor 项目均成功，耗时 5.372 秒。使用已安装 Maven 3.9.9、JDK 21.0.9、离线依赖及 `maven.compiler.fork=true`；保留的日志为 `tmp/optimization-01-compile.log`。

下表的测试来自同一会话中 14:13–14:28 的业务/架构运行与环境复核；本次 01 核对 Surefire XML、运行日志和源码差异，不重新运行未变化的业务。通过数 = 总数 - 失败 - 错误 - 跳过。

| 模块 | 总数 | 通过 | 失败 | 错误 | 跳过 |
|---|---:|---:|---:|---:|---:|
| agent-common | 24 | 23 | 0 | 0 | 1 |
| agent-runtime | 54 | 54 | 0 | 0 | 0 |
| agent-mcp | 11 | 11 | 0 | 0 | 0 |
| agent-gateway | 15 | 15 | 0 | 0 | 0 |
| agent-orchestrator-service | 77 | 74 | 0 | 0 | 3 |
| agent-auth-service | 163 | 163 | 0 | 0 | 0 |
| agent-chat-service | 61 | 61 | 0 | 0 | 0 |
| agent-rag-service | 140 | 136 | 0 | 0 | 4 |
| agent-media-service | 84 | 68 | 0 | 0 | 16 |
| agent-content-service | 16 | 16 | 0 | 0 | 0 |
| 业务合计 | 645 | 621 | 0 | 0 | 24 |
| agent-architecture-tests | 56 | 56 | 0 | 0 | 0 |
| Java 总计 | 701 | 677 | 0 | 0 | 24 |

`platform-parent`、`agent-api` 与根聚合器在该次运行没有执行测试，不将其计作失败，也不虚构通过数。

### 4.1 已发生的环境错误与复核

不能把此表表述为“一次完整命令全部通过”。完整运行中，Auth 的 42 项因 Windows 临时目录访问被拒绝报错；RAG 的 22 项也受到临时目录权限影响，其中一项表现为断言失败、21 项表现为错误。仅通过 Maven 用户属性设置 `java.io.tmpdir` 没有改变 JUnit 实际使用的目录。

随后在测试 JVM 启动时通过 `argLine` 固定可写临时目录，未修改测试、关闭清理或调整业务：

| 复核 | 结果 | 完成时间 +08:00 | 本地日志 |
|---|---|---|---|
| AlipayPaymentLoopTest、PaymentGatewayTest | 42 项通过，0 失败/错误 | 14:22:54 | `tmp/objective-review-auth-recheck.log` |
| DocumentStorageTest、GalleryMarkdownParserTest、GallerySourceTest、IngestionRecoveryTest、PackagedParserClasspathTest、TikaParserTest | 22 项通过，0 失败/错误 | 14:26:36 | `tmp/objective-review-rag-recheck.log` |
| 架构测试 | 56 项通过，0 失败/错误 | 14:28:15 | `tmp/objective-review-architecture.log` |

完整业务运行日志为 `tmp/objective-review-maven-local-temp.log`；首次中断日志为 `tmp/objective-review-maven.log`。聚合时使用对应测试类的最新复核结果，不重复累计同一用例。已观察到的失败已复核消除，不把环境异常归因于业务缺陷；没有据此证明所有外部环境都可用。

该 Windows 沙箱中 `mvn` 不在 PATH，`mvnw.cmd` 在 Maven 启动前因空 Target 数组报错，因此使用现有 Maven 3.9.9；本次未修改 Wrapper。运行时还出现依赖 JAR 路径访问警告，成功与否以命令结果及报告为准，不声称构建无警告。

### 4.2 跳过项必须保留

| 模块 / 测试 | 跳过数 | 本次报告中的条件 |
|---|---:|---|
| Common / InfrastructureContainersTest | 1 | Testcontainers 判定 Docker 不可用；该类另有 `RUN_INFRASTRUCTURE_TESTS` 环境门控 |
| Orchestrator / ExecutionMySqlConcurrencyTest | 2 | 未设置 `EXECUTION_TEST_MYSQL_URL` |
| Orchestrator / McpPublicDocumentationLiveTest | 1 | 未设置系统属性 `mcp.live=true` |
| RAG / MySqlIntegrationTest、MilvusIntegrationTest、GalleryMilvusIntegrationTest | 3 | 分别未设置 `RAG_MYSQL_INTEGRATION=true`、`RAG_MILVUS_INTEGRATION=true`、`PROMPT_GALLERY_MILVUS_INTEGRATION=true` |
| RAG / GalleryCatalogTest | 1 | 未设置系统属性 `gallery.catalog` |
| Media / ImageJobMySqlHardeningTest | 16 | 未设置 `MEDIA_TEST_MYSQL_URL`；测试还限制为隔离的本地 MySQL 地址 |

不得为了消除跳过而指向生产库。真实 InnoDB 锁、Milvus 和外部 MCP 行为仍未在本次验证；H2、模拟供应商和本地 HTTP 协议测试不能替代这些验收。

## 5. 复现入口

以下命令只用于包含测试、Wrapper 和运维脚本的完整开发工作区。在根目录使用 JDK 21，先验证 Wrapper 能启动；离线运行要求本地已有依赖。

```powershell
.\mvnw.cmd -o -B -ntp '-Dmaven.compiler.fork=true' -DskipTests compile
.\mvnw.cmd -o -B -ntp '-Dmaven.compiler.fork=true' test

# Windows 临时目录权限受限时，从 JVM 启动阶段指定可写目录
# 示例要求仓库绝对路径不含空格；含空格时需正确引用 argLine 中的路径
$verificationTemp = Join-Path (Get-Location).Path 'tmp/verification-temp'
New-Item -ItemType Directory -Force -Path $verificationTemp | Out-Null
.\mvnw.cmd -o -B -ntp '-Dmaven.compiler.fork=true' "-DargLine=-Djava.io.tmpdir=$verificationTemp" test

# 单独核对架构；上游没有指定测试时允许继续
.\mvnw.cmd -o -B -ntp -pl agent-architecture-tests -am '-Dmaven.compiler.fork=true' '-Dtest=com.bbb.exercise.agentdemo.architecture.*Test' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

如果 Wrapper 在环境初始化阶段报错，可用已安装且确认版本的 Maven 3.9.9 执行相同参数，不修改测试规避问题。原始命令中的本机可执行文件路径和依赖缓存路径不写入公开文档。

## 6. 其他验证与现有限制

- 既有 Python 验证共 47 项：监控 4、发布工具 24、备份 11、文档检查器自测 5、RAG 评测工具自测 3；通过 46、跳过 1。脚本自测成功不代表真实备份恢复、部署或模型质量通过。
- 文档链接扫描在本工作区发现 24 处缺失目标；它是检查失败，不能与“文档检查器自测 5 项通过”混为一谈。包含本地忽略文件中的链接，不能直接当作公开仓库 CI 的失败数量。本次只确保新增两份文档不引入新的失效链接，旧问题留待 10。
- RAG 离线语料检查：12 个文档族、32 个校准问题、16 个留出问题；语料 SHA-256 为 `28e015b03c4533c5235b239b3d169beea13c2d686b6883f94b0909da0057860a`，报告 `modelQualityExecution=NOT_EXECUTED`。
- 没有取得本次真实模型准确率、引用支持率、线上入口比例、延迟分位数或真实成本结果；固定问题复现和单元测试不能替代它们。
- GitHub 公开仓库默认排除 `docs/`，原发布工具也只允许核心源码。本次按用户明确要求在独立文档分支公开这两份文件，不修改默认分支与发布工具白名单；合并或后续自动导出时须先处理这两份文档的保留策略，不能直接推送开发历史。

## 7. 01 的验收边界

本次交付应满足：模块及端口事实可对应源码；两条接口的载体分别记录；逐模块通过/失败/错误/跳过及环境复核可追溯；未来任务有明确范围与验收；公开提交只包含这两份文档，父提交来自公开 main。

没有把行数、提示词关键词、常量数量或未执行的真实能力作为完成证据。后续业务修复、对外契约测试、质量采集、缺失文档恢复和部署均不属于 01。
