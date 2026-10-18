# agent-api 模块帮助文档

## 1. 模块作用与边界

本模块定义跨服务共享的数据结构和少量纯计算规则，让 Auth、Chat、RAG、Media、Orchestrator 使用同一套身份、模型调用和计费语义。它是普通 Java 库，不启动 Spring 容器，不提供 HTTP 路由，不访问数据库和模型供应商。

源码根目录：[api](src/main/java/com/bbb/exercise/agentdemo/api)。当前 POM 仅直接依赖 Lombok；契约主要使用 Java `record`，聊天输入 DTO 使用 Lombok 生成访问方法。

## 2. 包和核心类型

| 包 / 类型 | 作用 | 设计重点 |
| --- | --- | --- |
| [dto](src/main/java/com/bbb/exercise/agentdemo/api/dto) | 聊天请求、附件引用、用户身份响应 | 附件传 `assetId`，不在聊天请求内传图片二进制 |
| [ChatIdentity](src/main/java/com/bbb/exercise/agentdemo/api/identity/ChatIdentity.java) | 服务端使用的租户、用户和认证状态 | 身份由可信 HTTP 上下文解析，不能信任请求 JSON 自报的身份 |
| [ModelContracts](src/main/java/com/bbb/exercise/agentdemo/api/model/ModelContracts.java) | Completion、Embedding、Rerank、生成事件 | 记录实际型号、供应商、兜底、实测用量和未确认预留量 |
| [BillingContracts](src/main/java/com/bbb/exercise/agentdemo/api/billing/BillingContracts.java) | 预留、结算、调用组、尝试、钱包和消费记录 | 金额使用整数微元；缺失 Token 用量允许为 `null` |
| [RagContracts](src/main/java/com/bbb/exercise/agentdemo/api/rag/RagContracts.java) | 文档、证据、来源、问答和固定版本会话 | 结果携带文档 ID、切片 ID、索引版本和验证结果 |

### 聊天请求

`ChatRequest` 包含 `question`、可选 `sessionId` 和 `attachments`。`ChatAttachmentRequest` 保存资产 ID、类型、原文件名和大小。真正读取资产时，Media 再检查其所有权、上传状态和有效期，不能因为 DTO 包含某个 ID 就直接授权访问。

`UserIdentityDto` 包含公开用户 ID、租户、认证状态、用户名、角色和权限。角色用于描述用户身份，权限用于具体业务授权；这些字段由 Auth 产生。

### 模型调用契约

`CompletionRequest` 通过 `callId` 标识一次逻辑调用，使用 `mode` 区分聊天、摘要、文档问答、校验和 Agent 决策。可选 `tokenBudget` 是本次调用允许占用的预算边界。

`Completion` 的 `inputTokens` / `outputTokens` 表示供应商实测用量，`reservedTokens` / `unknownTokens` 描述预算与未知尝试。不能把预算估算当作实测用量，也不能把未确认用量当成零消费。

`GenerationEvent(type, generationVersion, payload)` 用于流式过程。`generationVersion` 使消费者能分辨主模型输出与后续替换内容，避免把两版答案直接拼接。

### 计费契约

计费分为简单的 `Reserve` / `Settlement` 和包含主、备用尝试的调用组协议。`GroupReserve` 带参数哈希，`AttemptAction` / `AttemptReceipt` 带 generation，防止同一调用 ID 被改参重用或旧尝试覆盖新状态。

`Wallet` 包含余额、已预留金额、免费图片、免费 Token、欠费和阻塞状态。单位换算：`1 元 = 1,000,000 微元`；支付订单中的分属于另一单位，不能混用。

### RAG 契约

`Evidence` 是参与模型生成的证据，`Source` 是可返回给用户的引用。`ConversationView.indexVersions` 将文档会话固定到具体索引版本，后续追问不应悄悄切到新文档版本。`Answer` 同时返回验证状态、降级标记、来源和模型用量。

## 3. 纯计算辅助能力

- [ModelCapability](src/main/java/com/bbb/exercise/agentdemo/api/model/ModelCapability.java)：定义模型能力，供服务选择合适的模型配置。
- [ModelProviderRegistry](src/main/java/com/bbb/exercise/agentdemo/api/model/ModelProviderRegistry.java)：校验供应商、能力和型号组合，提供协议端点的默认描述。
- [ModelFingerprint](src/main/java/com/bbb/exercise/agentdemo/api/model/ModelFingerprint.java)：提供模型空间标识，支持 RAG 判断向量空间是否兼容。
- [ModelPrompts](src/main/java/com/bbb/exercise/agentdemo/api/model/ModelPrompts.java)：集中公共护栏与静态任务规则；`assemble(mode, supplement, fallback)` 是预算估算和供应商发送共同使用的最终 system 组装入口。`IMAGE_EVIDENCE` 只选择图片证据提取规则，不新增运行时或计费模式。
- [TokenBudgetEstimator](src/main/java/com/bbb/exercise/agentdemo/api/model/TokenBudgetEstimator.java)：按 UTF-8 字节、协议开销和输出上界保守估算预算；Completion 的输出上界当前为 2048。它不是供应商 tokenizer。

## 4. 使用与扩展

```xml
<dependency>
    <groupId>com.bbb.exercise</groupId>
    <artifactId>agent-api</artifactId>
    <version>${project.version}</version>
</dependency>
```

新增字段时，先明确空值、单位、所有权、幂等和旧回执反序列化的语义，再同时更新调用方与提供方。现有部分 record 保留重载构造方法以兼容旧调用点；这不代表任意字段变更都能兼容网络协议和数据库中的历史 JSON。

跨服务 DTO 不应加入 Mapper、Service、数据库实体或某个供应商 SDK 类型。服务通过 HTTP 交换本模块的契约，不能为了复用实现而依赖另一个服务 JAR。

## 5. 构建和验证

在仓库根目录执行：

```powershell
mvn -pl agent-api -am '-Dmaven.compiler.fork=true' package
mvn -pl agent-architecture-tests -am '-Dmaven.compiler.fork=true' test
```

本模块没有独立测试源码，实际协议行为由 Chat、Runtime、Auth、RAG 等消费方测试覆盖，架构模块检查死契约和边界。变更共享字段后，应运行完整 reactor 的 `verify`。

相关说明：[公共基础能力](../agent-common/README.md)、[运行时客户端](../agent-runtime/README.md)、[文档检索](../agent-rag-service/README.md)。
