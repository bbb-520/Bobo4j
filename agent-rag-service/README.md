# agent-rag-service 模块帮助文档

## 1. 模块作用与边界

RAG 提供文档上传、隔离解析、语义切片、向量索引、摘要、固定版本文档会话、混合检索和带引用的证据问答。默认端口 `18086`，启动类为 [RagServiceApplication](src/main/java/com/bbb/exercise/agentdemo/ragservice/RagServiceApplication.java)。

MySQL 保存文档 / 切片 / 任务 / 回执和会话，Milvus 保存向量索引，文件存储卷保存原件与解析产物。文本生成通过 Chat 内部接口，模型配置、计费与实际供应商访问复用 Auth / Runtime。Orchestrator 对浏览器提供持久化问答执行入口。

## 2. 代码导航

| 包 | 核心职责 |
| --- | --- |
| [web](src/main/java/com/bbb/exercise/agentdemo/ragservice/web) | 浏览器文档 API、内部检索 / 回答 API、错误转换 |
| [storage](src/main/java/com/bbb/exercise/agentdemo/ragservice/storage) | 文档上传、原件 / 工作目录及清理 |
| [parsing](src/main/java/com/bbb/exercise/agentdemo/ragservice/parsing) | 隔离 Tika 子进程和解析产物格式 |
| [chunking](src/main/java/com/bbb/exercise/agentdemo/ragservice/chunking) | 语义边界、标题 / 表格处理和来源范围 |
| [processing](src/main/java/com/bbb/exercise/agentdemo/ragservice/processing) | 异步入库 Worker、分层摘要和模型预算 |
| [retrieval](src/main/java/com/bbb/exercise/agentdemo/ragservice/retrieval) | Milvus、BM25、RRF、Rerank 和证据窗口 |
| [answer](src/main/java/com/bbb/exercise/agentdemo/ragservice/answer) | 基于证据生成、相关性 / 事实支持 / 引用验证 |
| [persistence](src/main/java/com/bbb/exercise/agentdemo/ragservice/persistence) | 所有权、租约、状态、回执、快照和预算 |
| evaluation（本地路径 `evaluation`，不随核心源码发布） | 语料、真实观测采集和离线评分 |

## 3. 文档入库如何实现

```mermaid
flowchart LR
    U[上传并保存原件] --> P[Tika 解析]
    P --> C[语义切片]
    C --> E[Embedding 与缓存]
    E --> I[Milvus 写入和校验]
    I --> S[Map Reduce 摘要]
    S --> R[文档索引与摘要状态]
```

### 3.1 上传与任务持久化

`POST /api/documents` 接收 multipart 字段 `file` 和 `X-Request-ID`，返回 HTTP 202 及文档视图。客户端重试相同上传应保留同一请求 ID；文件接收成功并不意味着索引或摘要完成。

`DocumentStorage` 保存原件并登记任务，`IngestionWorker` 默认每 1000ms 领取任务。状态推进使用数据库租约 / fencing，旧 Worker 不能在失去租约后覆盖新 Worker 结果。每完成一段工作会保存产物或回执并让出任务，使服务重启后能从已持久化阶段继续。

### 3.2 隔离解析

[IsolatedTikaParser](src/main/java/com/bbb/exercise/agentdemo/ragservice/parsing/IsolatedTikaParser.java) 启动独立 Java 子进程，最大堆 512 MiB，默认解析超时 120 秒；清理继承环境，使用独立工作目录，超时会终止子进程。可执行 JAR 模式下会提取解析所需 classpath。

Tika 输出结构化 Block，保留标题、页码（有则保留）、段落 / 表格类型和字符范围。无文本层、加密 / 损坏、输出过量和资源不足分别返回明确错误；当前不提供自动 OCR，扫描件可能得到 `NO_TEXT_LAYER`。

### 3.3 语义切片和向量空间

入库先生成基础文本单元，超长段落按句子 / 换行切分，表格延续片段保留标题行。邻近单元窗口经 Embedding 后计算余弦相似度；[SemanticChunker](src/main/java/com/bbb/exercise/agentdemo/ragservice/chunking/SemanticChunker.java) 结合相似度低点、标题变化和长度上限形成切片。当前 chunking 版本 `semantic-window-v1`，目标切片上限 2048 个 code point。

切片 ID 由文档版本、来源范围和内容哈希生成，元数据保留原文范围与来源顺序。Embedding 缓存键包含租户、用户、模型指纹、维度、切片规则版本和内容哈希，避免跨用户和跨空间复用。

向量批次写入 Milvus 后验证可读数量，才将索引标记 READY。默认 1024 维；换 Embedding 模型、端点 / 空间或维度后可能需要重建索引，不能只修改模型名继续搜索旧向量。相关错误为 `EMBEDDING_REBUILD_REQUIRED`。

模型指纹还包含 credentialId。当前 Auth 用凭据的 SHA-256 作为 credentialId，因此更换 Embedding 凭据也会改变空间标识；轮换前应规划已有文档的重建，而不是只核对向量维度。

### 3.4 分层摘要

每 8 个切片生成一个 map 摘要，每 4 个摘要递归 reduce。`rag_summary_node` 保存完成节点，恢复时复用节点而不重复调用。摘要状态和覆盖率独立于索引状态，索引 READY 不代表摘要已覆盖全文。

余额 / 准入不足进入 `WAITING_FOR_BUDGET`，派发结果不明进入 `WAITING_FOR_RECONCILIATION`。删除文档登记 CLEANUP 工作，由 Worker 清除向量、原件和工作文件；不只删除数据库行。

## 4. 文档会话与混合检索

创建文档会话时提交 `documentIds`，Repository 检查归属并固定 `indexVersions`。问答检索范围只能是当前用户、租户和这些固定版本。

[HybridRetriever](src/main/java/com/bbb/exercise/agentdemo/ragservice/retrieval/HybridRetriever.java) 的流程为：

1. 保存请求参数、历史和配置快照，重复 callId 读取已完成阶段。
2. 多轮追问根据最近已提交历史改写为独立检索问题，保留编号和数值。
3. Dense 向量召回 30 条，应用层 BM25 关键词召回 30 条。
4. 采用 RRF 融合，单路分数为 `weight / (60 + rank)`，最多保留 20 个候选。随后仅按两路召回的去重切片 ID 从 MySQL 批量读取原文（通常最多 60 条），不加载会话文档的全部切片；保留融合外命中的原文供 `recallEvidence` 评估 trace 使用。每批最多 100 个 ID，SQL 同时限定租户、用户、固定的文档 / 版本组合与 Embedding 指纹，空召回不读取切片；重排候选顺序保持融合排名。
5. 调用 Rerank，默认最低分数 `0.25`。
6. 按内容哈希去重，选取最多 8 条证据，总证据 UTF-8 字节预算 8192；通过 EvidenceWindow 截取相关原文范围。
7. 保存检索 trace，包括 Dense、BM25、融合、Rerank、选中证据与配置。

Dense / 关键词某些索引层异常可以退化为单路并标记 `degraded=true`；模型预算、签名、向量空间冲突等错误不会全部吞掉降级。Rerank 也不是无条件可跳过的步骤。

## 5. 回答、引用和验证

[DocumentAnswerService](src/main/java/com/bbb/exercise/agentdemo/ragservice/answer/DocumentAnswerService.java) 按 `callId` 领取回答租约，绑定身份与问题，执行检索和证据回答。无证据时返回明确拒答 `NO_EVIDENCE`。

证据编号为 `[S1]`、`[S2]` 等，提示要求事实段落引用这些编号，文档和用户内容作为不可信数据处理。`GroundingEvaluator` 执行相关性与事实支持校验，`CitationValidator` 检查引用编号与引用覆盖。

初稿未通过时最多进行一次修复；两次失败后给出受控拒答 `FAIL`。校验服务不可用时不提交为成功答案。只有通过验证的最终结果才提交文档会话历史和来源。

流式过程可以包含草稿事件，但草稿不等于已验证最终答案；消费者以 `answer_committed` 为准，遵守 generationVersion 和替换事件。答案返回实际模型、供应商、兜底、用量和未知预留。

检索、改写、生成和校验共享复合 Token 预算，RagModelGateway 持久化子调用预留与结算；重试不能通过新子 ID 绕过原预算。

## 6. HTTP API 与使用顺序

| 方法与路径 | 用途 |
| --- | --- |
| `POST /api/documents` | multipart 上传；带稳定 X-Request-ID |
| `GET /api/documents`、`GET /api/documents/{id}` | 文档列表、索引 / 摘要进度和错误 |
| `GET /api/documents/{id}/summary` | 摘要正文与覆盖率 |
| `POST /api/documents/{id}/retry` | 显式恢复 / 重试，仍受回执和状态校验 |
| `DELETE /api/documents/{id}` | 删除并安排资源清理 |
| `GET /api/documents/{id}/sources/{chunkId}` | 查看原文切片 |
| `POST /api/document-conversations` | 固定选中文档版本 |
| `GET /api/document-conversations/{id}` | 文档范围和已提交历史 |
| `GET /api/document-conversations/{id}/retrieval-traces` | 所有者范围内的检索 trace |
| `POST /internal/rag/retrieve`、`/answer`、`/answer-stream` | 签名内部检索和回答 |
| `GET /internal/rag/calls/{callId}` | 恢复 / 对账所需调用状态 |
| `GET /internal/rag/documents/{id}/sections` | 会话范围内最多 8 段原文 |

浏览器实际问答顺序：登录并取 CSRF → 上传 → 查询到 `indexStatus=READY` → 创建文档会话 → `POST /api/agent-executions`，设置 `type=DOCUMENT_QA` 和 `documentConversationId` → 订阅持久执行事件。完整执行协议见 [Orchestrator](../agent-orchestrator-service/README.md)。本模块没有单独的公开 `/api/.../answer` 接口。

## 7. 数据、配置与运行

迁移目录：[db/migration](src/main/resources/db/migration)，历史表 `flyway_schema_history_rag`。主要表：`rag_document`、`rag_job`、`rag_chunk`、`rag_embedding_cache`、`rag_summary_node`、`rag_conversation`、`rag_answer`、`rag_trace`、`rag_model_call`、`rag_stage_snapshot`、`rag_call_budget`。

| 配置 | 默认值 / 说明 |
| --- | --- |
| `RAG_SERVICE_PORT` | `18086`，与其他模块常用 SERVER_PORT 不同 |
| `RAG_STORAGE_ROOT` | `./data/rag`，必须可写且持久化 |
| `MILVUS_URI` / `MILVUS_TOKEN` | `http://localhost:19530` / 可选鉴权 |
| `rag.milvus.collection` | `agent_rag_v2` |
| `rag.dimensions` | `1024` |
| `rag.maximum-characters` | `20000000` |
| `RAG_RERANK_MINIMUM` / `RAG_DENSE_RRF_WEIGHT` | `0.25` / `1.0` |
| `AUTH_SERVICE_URL` / `CHAT_SERVICE_URL` | Auth / Chat 内部地址 |
| `MYSQL_*`、`NACOS_*`、内部签名配置 | 数据、发现 / 配置与服务身份 |

上传限制、Nacos 和 Redis 配置见 [application.yml](src/main/resources/application.yml)。模型 Key 主要配置在 Auth，Rerank 需要实际区域 / 工作空间 URL。

```powershell
# 仓库根目录；先准备 MySQL、Milvus、Auth、Chat 和 Nacos
mvn -pl agent-rag-service -am '-Dmaven.compiler.fork=true' package
java -jar agent-rag-service/target/agent-rag-service-0.0.1-SNAPSHOT.jar
```

## 8. 验证与评测

另开终端运行 `mvn -pl agent-rag-service -am '-Dmaven.compiler.fork=true' test`。测试目录（本地路径 `src/test/java/com/bbb/exercise/agentdemo/ragservice`，不随核心源码发布） 覆盖解析、切片、混合融合、引用、长中文答案、向量空间、预算和入库恢复。

真实 MySQL / Milvus 测试分别由 `RAG_MYSQL_INTEGRATION=true` 和 `RAG_MILVUS_INTEGRATION=true` 启用，需独立测试库 / collection。启用前检查测试类中的连接参数和清理行为，不指向业务数据库。

evaluate.py（本地路径 `evaluation/evaluate.py`，不随核心源码发布） 验证 12 个文档族、48 个问题（32 校准 / 16 留出），可生成测试 HTML 和离线报告：

```powershell
python agent-rag-service/evaluation/evaluate.py --export-documents data/rag-evaluation/documents --output data/rag-evaluation/corpus-report.json
python agent-rag-service/evaluation/collect.py --help
```

没有真实观测时报告标记 `NOT_EXECUTED`，不能据此声称检索或回答质量已验证。`collect.py` 要求显式 `--allow-paid-models`、会话 token 和模型版本快照，调用真实应用并正常计费。写请求先获取 CSRF token，并保留会话及 CSRF Cookie；失败请求不会自动重发。完整采集流程和本地 HTTP 回归见 评测指南（本地路径 `evaluation/README.md`，不随核心源码发布）。离线重放 RRF 参数不能替代更换候选集后的完整重测，答案质量还需独立人工标注。

## 9. 排错与扩展

- `NO_TEXT_LAYER`：检查文件是否扫描件，当前没有 OCR 流程。
- `PARSER_UNAVAILABLE`：检查 Java 子进程启动、工作目录权限、打包 classpath 和磁盘。
- `EMBEDDING_REBUILD_REQUIRED`：核对模型指纹 / 维度，重建受影响文档索引。
- 入库停在等待状态：查看钱包、模型调用回执、RAG 子预算和文档错误码。
- 查不到已上传文档：确认用户 / 租户、索引状态和文档会话固定版本。
- 有检索却拒答：检查 trace 中的重排序阈值、证据窗口与验证结果；拒答可能是证据不足的正常结果。

新增文档处理或检索能力时，必须保留所有权、版本、来源范围、空间指纹、阶段回执和预算语义，并增加对应故障恢复验证。
