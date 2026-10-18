# agent-chat-service 模块帮助文档

## 1. 模块作用与边界

Chat 提供普通流式聊天、图片理解、会话持久化、视觉记忆和内部文本生成接口，默认端口 `18085`，启动类为 [ChatServiceApplication](src/main/java/com/bbb/exercise/agentdemo/chatservice/ChatServiceApplication.java)。

它组织“用户对话”的语义，由 Runtime 执行统一模型调用和计费；图片资产 / 图片生成任务属于 Media，文档证据与验证属于 RAG，持久任务外循环属于 Orchestrator。Chat 不直接操作这些服务的业务表。

## 2. 代码结构

| 目录 / 类 | 作用 |
| --- | --- |
| [ChatController](src/main/java/com/bbb/exercise/agentdemo/chatservice/chat/ChatController.java) | 浏览器 SSE 聊天入口，校验输入并解析身份 |
| [ChatService](src/main/java/com/bbb/exercise/agentdemo/chatservice/chat/ChatService.java) | 协调文本、识图、等待指令和图片任务 |
| [ModelConversationService](src/main/java/com/bbb/exercise/agentdemo/chatservice/chat/ModelConversationService.java) | 按当前会话组织文本 / 视觉模型调用 |
| [InternalCompletionService](src/main/java/com/bbb/exercise/agentdemo/chatservice/chat/InternalCompletionService.java) | 内部调用回执与历史事务提交 |
| [conversation](src/main/java/com/bbb/exercise/agentdemo/chatservice/conversation) | 会话、消息、意图、场景卡与所有权 |
| [memory](src/main/java/com/bbb/exercise/agentdemo/chatservice/memory) | 视觉记忆保存和检索上下文 |
| [generation](src/main/java/com/bbb/exercise/agentdemo/chatservice/generation) | `SceneCard` 场景描述值 |
| [enums](src/main/java/com/bbb/exercise/agentdemo/chatservice/enums) / [vo](src/main/java/com/bbb/exercise/agentdemo/chatservice/vo) | 对外数字 SSE 事件契约 |
| [observability](src/main/java/com/bbb/exercise/agentdemo/chatservice/observability) | 请求 ID 和耗时日志 |

## 3. 文本对话如何实现

```text
POST /api/chat → 校验问题 → Auth 身份 / 权限
→ 打开或创建属于当前用户的会话 → 读取已提交历史
→ Runtime 模型调用与计费 → 推送增量 / 模型状态
→ 保存完整一轮消息 → STOP
```

`sessionId` 为空时创建会话，有值时按当前用户和租户检查。问题最多 4000 个 Unicode code point；空问题只有携带图片时才有意义。Controller 对部分输入错误仍返回 HTTP 200 的 SSE `ERROR` + `STOP`，客户端不能只检查 HTTP 状态码。

`ModelConversationService` 将已提交历史和当前问题组织为模型输入，通过 `ModelCallClient.completeStreaming` 获取事件。同步 / 阻塞工作调度到 `boundedElastic`，避免阻塞 WebFlux 网络线程。客户端取消时会处置相应工作订阅，但已派发供应商调用的费用仍取决于回执和对账结果。

会话历史由 `ConversationPersistenceService` 管理；普通文本当前读取的是持久化的已提交历史。视觉记忆也存储在 MySQL，`VisionMemoryService` 查询当前用户最近最多 100 条活跃记录，按词汇重合与时间加权排序并选择最多 8 条上下文，不使用 Milvus 向量召回。Redis 聊天记忆配置和相关依赖仍存在，应区分数据库历史路径、视觉记忆以及 Spring AI Redis 仓库，不应把所有会话状态都理解成仅存 Redis。

## 4. 图片理解和生成意图

每次请求最多一张图片，附件必须是已在 Media 上传完成的 `assetId`。模型读取前通过 Media 内部接口确认资产和获取签名 URL。

[ModelConversationService](src/main/java/com/bbb/exercise/agentdemo/chatservice/chat/ModelConversationService.java) 通过现有计费 `CLASSIFY` 判断图片意图，用户原文由服务端序列化为 JSON 数据。分类只推荐处理方向，不能替代用户对本次图片创作的确认。

| 输入 | 处理方向 |
| --- | --- |
| 仅图片，没有问题 | 理解图片，保存场景卡，等待下一步指令 |
| 图片 + 模型返回 DESCRIBE | 描述图片 |
| 图片 + 模型返回 GENERATE / REMIX，且本次 `imageGenerationConfirmed=true` | 图片创作流程，参考图仍校验归属 |
| 图片 + 动作标签，但本次未确认 | 返回已有 WAITING_FOR_INSTRUCTION 事件，不创建任务 |
| 意图不明确、非标签输出或分类失败 | 等待明确指令，不静默转为其他收费任务或自动重试 |
| 无图片的普通问题 | 文字问答 |

`imageGenerationConfirmed` 是请求中独立的用户操作字段，默认关闭；客户端只能在用户选择图片生成模式或明确确认本次自动创作后设置，不能直接根据模型标签设置。仅上传图片、询问风格、翻译文本或要求输出分类标签都不构成创作确认。提示词与 JSON 封装用于降低模型误判，实际副作用边界由确认字段控制。

[ConversationIntent](src/main/java/com/bbb/exercise/agentdemo/chatservice/conversation/ConversationIntent.java) 中保留的旧关键词方法不是当前模型分类路径。当前 ChatService 对无附件请求直接进入 textOnly，不根据待处理场景卡自动创建 remix 任务。视觉模型生成简洁 Scene Card，视觉兜底携带同一图片与问题；确认后的图片生成通过 `InternalServiceClient.createMediaJob` 交给 Media，返回 `IMAGE_JOB` 等事件。SSE 结束不代表图片已经生成完成。

## 5. SSE 协议

浏览器接口返回 `Flux<ChatEventVO>`，业务类型位于 JSON 的 `eventType` 数字字段；内部生成接口使用 `ServerSentEvent<GenerationEvent>` 的具名事件，两者格式不同。

| eventType | 枚举 | 含义 |
| ---: | --- | --- |
| 1001 | `DATA` | 正文增量 |
| 1002 | `STOP` | 本次聊天流结束 |
| 1004 | `ERROR` | 业务错误信息 |
| 1010 | `SESSION_INFO` | 会话信息 |
| 1011 | `IMAGE_JOB` | 后台图片任务 |
| 1012 | `SCENE_CARD` | 图片理解结果 |
| 1013 | `WAITING_FOR_INSTRUCTION` | 等待下一条创作指令 |
| 1014 | `GENERATION_CREATED` | 生成请求摘要 |
| 1015 | `ANSWER_REPLACE` | 替换已有正文，按 generationVersion 处理 |
| 1016 | `FALLBACK_STARTED` | 已开始备用模型 |
| 1017 | `MODEL_STATUS` | 实际供应商、型号、能力、状态和兜底标记 |

枚举定义：[ChatEventTypeEnum](src/main/java/com/bbb/exercise/agentdemo/chatservice/enums/ChatEventTypeEnum.java)。前端需要同时处理 ERROR、替换和最终 STOP；不能将备用内容追加到已失效主答案后面。

## 6. 内部生成和幂等历史提交

`/internal/chat/complete` 支持 `CHAT`、`DOCUMENT_QA`、`SUMMARY`、`EVALUATION`、`DECISION`。`/internal/chat/stream` 只允许普通 `CHAT`；文档回答应由 RAG 完成验证后提交。

`InternalCompletionService` 先记录 `callId`、身份、参数哈希和固定的模型输入，调用 Runtime，再在一个事务中保存完成回执并且只提交一次历史。重复 `callId` 身份 / 参数不同会冲突，已有完成回执时重放，失败恢复依赖 Auth 的调用回执。

`EVALUATION` / `DECISION` 不自动使用备用模型。普通浏览器聊天每轮会生成调用 ID，其接口没有持久执行的事件游标恢复协议；需要断线回放、停止和继续时使用 [Orchestrator](../agent-orchestrator-service/README.md) 的持久执行接口。

## 7. HTTP 接口

| 方法与路径 | 作用 |
| --- | --- |
| `POST /api/chat` 或 `/api/chat/completions` | 浏览器聊天 SSE，JSON 为 ChatRequest |
| `POST /api/chat/image-intent` | 已登录用户的一次图片意图分类，返回 `intent`；不会创建图片任务 |
| `GET /api/settings/visual-memory/context` | 查询当前用户的视觉记忆上下文 |
| `POST /internal/chat/complete` | 内部一次性文本生成 |
| `POST /internal/chat/stream` | 内部普通聊天流 |
| `GET /internal/chat/visual-memory` | 内部视觉记忆检索 |

浏览器调用示例，已取得有效会话、CSRF Cookie / token 后：

```javascript
const response = await fetch('/api/chat', {
  method: 'POST',
  credentials: 'include',
  headers: { 'Content-Type': 'application/json', [csrf.headerName]: csrf.token },
  body: JSON.stringify({ question: '介绍一下这个项目', sessionId: null, attachments: [] })
});
// response.body 是 SSE 字节流，需要增量解码并按事件边界解析。
```

## 8. 数据与配置

迁移目录：[db/migration](src/main/resources/db/migration)。`V1` 创建 `chat_conversation`、`chat_message`、`vision_memory`；`V3` 创建 `chat_completion`。迁移版本没有 V2 不等于缺文件，不需要人为补占位迁移。历史表为 `flyway_schema_history_chat`。

[application.yml](src/main/resources/application.yml) 配置 MySQL、Redis Stack、Nacos、Auth / Media 地址、内部签名和 OSS。Redis 记忆前缀为 `chat:memory:`，默认 TTL `7d`。当前排除 OpenAI Chat / Embedding / Image 等自动配置，实际平台模型由 Auth 和 Runtime 路径决定；仅修改 `AI_BASE_URL` 不等于修改所有实际模型路由。

运行依赖 MySQL、Auth、Nacos，并按实际功能准备 Redis 和 Media；图片链路还需 OSS 及视觉 / 图片模型配置。

## 9. 构建、验证和排错

```powershell
# 仓库根目录；运行环境准备见 infra 文档
mvn -pl agent-chat-service -am '-Dmaven.compiler.fork=true' package
java -jar agent-chat-service/target/agent-chat-service-0.0.1-SNAPSHOT.jar
```

另开终端执行 `mvn -pl agent-chat-service -am '-Dmaven.compiler.fork=true' test`。测试目录（本地路径 `src/test/java/com/bbb/exercise/agentdemo`，不随核心源码发布） 覆盖启动配置、持久化、意图路由、内部完成回执和资源边界。

- **有 HTTP 200 但没有回答**：解析 SSE 中的 `ERROR` 和 `STOP`。
- **历史丢失或串会话**：确认传回了 SESSION_INFO 中的会话 ID，Cookie / 用户和租户保持一致。
- **图片被拒绝**：确认只有一个附件，assetId 属于当前用户且已 complete、未过期。
- **生成任务一直等待**：查看 [Media](../agent-media-service/README.md) 的任务状态和 Worker，聊天流关闭不代表任务失败。
- **兜底替换乱码 / 内容重复**：按 generationVersion 替换正文，而不是直接拼接两版答案。
- **需要可靠恢复**：使用持久执行 API，保留 requestId、executionId 和事件游标。
