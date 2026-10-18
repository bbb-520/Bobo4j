<p align="center">
  <img src="assets/readme/hero.svg" width="100%" alt="BoboWorld4J：基于 Java 与 Spring AI，让对话、知识与工具在同一条任务链中协作。" />
</p>

<p align="center">
  <a href="platform-parent/pom.xml"><img src="https://img.shields.io/badge/Java-21-e76f51?style=flat-square" alt="Java 21" /></a>
  <a href="platform-parent/pom.xml"><img src="https://img.shields.io/badge/Spring_Boot-4.1.1-6db33f?style=flat-square" alt="Spring Boot 4.1.1" /></a>
  <a href="platform-parent/pom.xml"><img src="https://img.shields.io/badge/Spring_AI-2.0.1-27aaa5?style=flat-square" alt="Spring AI 2.0.1" /></a>
  <a href="#快速开始"><img src="https://img.shields.io/badge/Maven-3.9%2B-e9b44c?style=flat-square" alt="Maven 3.9 及以上" /></a>
  <a href="https://github.com/bbb-520/Bobo4j/issues"><img src="https://img.shields.io/badge/Issues-欢迎反馈-f56c72?style=flat-square" alt="欢迎反馈问题" /></a>
</p>

<p align="center">
  <a href="#它是什么"><b>它是什么</b></a> ·
  <a href="#功能特性">功能特性</a> ·
  <a href="#一个任务是怎么走完的">任务流程</a> ·
  <a href="#它是怎么搭起来的">架构</a> ·
  <a href="#快速开始">快速开始</a> ·
  <a href="#agent-如何使用工具">工具调用</a> ·
  <a href="#安全与边界">安全与边界</a> ·
  <a href="#开发与维护">开发与维护</a>
</p>

> 项目持续迭代中。本仓库集中维护后端源码、自动化测试、构建定义、通用维护脚本与 README 配图；配套前端在 [Bobo_web](https://github.com/bbb-520/Bobo_web) 独立维护。以下能力以当前代码为准，实际可用性还取决于部署配置和外部服务。

## 它是什么

**BoboWorld4J（BOBO4J）是一个基于 Java 21 与 Spring AI 的 AI 应用平台后端。**

把模型对话、文档知识、Agent 工具和图片创作接到同一套账户与任务体系里：上传一份文档，查看摘要并带着来源连续追问；交给 Agent 一个问题，让它检索证据、选择工具、保存执行进度；遇到中断或供应商结果不明时，保留状态和回执，继续查询与核对。

<p align="center">
  <a href="https://www.boboo.xin"><b>在线体验</b></a> ·
  <a href="https://github.com/bbb-520/Bobo_web">前端仓库</a> ·
  <a href="https://github.com/bbb-520/Bobo4j/issues">报告问题</a>
</p>

## 功能特性

<table>
  <tr>
    <td width="50%" align="center">
      <h3>💬 流式对话与识图</h3>
      <p>流式回答、会话历史、图片理解与视觉记忆检索。让文字、图片和后续追问处于同一个会话上下文。</p>
    </td>
    <td width="50%" align="center">
      <h3>📚 带来源的文档问答</h3>
      <p>文件解析、语义切片、混合检索、重排序与摘要。回答逐段附上来源，并校验引用和事实支持。</p>
    </td>
  </tr>
  <tr>
    <td width="50%" align="center">
      <h3>🧭 可恢复的 Agent</h3>
      <p>模型选择工具，服务端校验后执行。任务、步骤和事件持久化，支持预算约束、停止、恢复与游标回放。</p>
    </td>
    <td width="50%" align="center">
      <h3>🎨 图片与内容创作</h3>
      <p>图片生成、参考图编辑、图片资产与内容作品服务。生成任务独立保存，便于查询结果和核对消费。</p>
    </td>
  </tr>
  <tr>
    <td width="50%" align="center">
      <h3>🧾 账户与用量结算</h3>
      <p>邮箱验证码、账户会话、免费 Token、钱包、消费记录与支付宝支付。按实际回执幂等结算。</p>
    </td>
    <td width="50%" align="center">
      <h3>🛡️ 可追溯的服务协作</h3>
      <p>服务间身份签名、用户与租户隔离、权限和审计。模型主备调用、健康检查与指标帮助定位故障。</p>
    </td>
  </tr>
</table>

## 一个任务是怎么走完的

<a href="assets/readme/task-flow.svg"><img src="assets/readme/task-flow.svg" width="100%" alt="任务流程：发起任务、模型决策、受控调用、结果校验、持久回执；校验未通过时有限修复或明确拒答。" /></a>

Agent 接收问题和选定的会话范围，结合已有证据输出结构化决策。服务端检查工具白名单、参数、身份和执行预算，再把调用交给相应领域服务；每一步保存状态及回执，供停止、恢复和事件回放使用。

文档问答还要经过**相关性、来源引用和事实支持校验**。没有证据时明确说明；引用不合格时提供具体修复反馈，修复仍失败则拒绝输出未经支持的答案。供应商调用结果不明时保留核对状态，避免把未知结果记成成功或重复派发收费请求。

## 它是怎么搭起来的

<a href="assets/readme/architecture.svg"><img src="assets/readme/architecture.svg" width="100%" alt="架构总览：网页前端、网关与账户服务；持久化 Agent 编排；聊天、RAG、图片与内容服务；共享模型运行时、协议和身份签名；MySQL、Redis、Milvus、Nacos及外部供应商。" /></a>

| 模块 | 负责什么 |
| --- | --- |
| [`platform-parent`](platform-parent/pom.xml) | 统一依赖版本与构建配置 |
| [`agent-api`](agent-api/src/main) | 跨服务协议、DTO 与模型能力定义 |
| [`agent-common`](agent-common/src/main) | 公共响应、身份签名与基础配置 |
| [`agent-runtime`](agent-runtime/src/main) | 模型调用、内部服务客户端与对象存储能力 |
| [`agent-mcp`](agent-mcp/src/main) | MCP 连接、工具发现、定义校验和公开资料结果处理 |
| [`agent-gateway`](agent-gateway/src/main) | 请求路由、访问控制与流量管理 |
| [`agent-auth-service`](agent-auth-service/src/main) | 账户、权限、计费、模型配置与支付 |
| [`agent-chat-service`](agent-chat-service/src/main) | 对话、图片理解与会话记忆 |
| [`agent-rag-service`](agent-rag-service/src/main) | 文档处理、索引、检索、摘要与问答 |
| [`agent-orchestrator-service`](agent-orchestrator-service/src/main) | 持久化 Agent 执行与工具协调 |
| [`agent-media-service`](agent-media-service/src/main) | 图片资产、生成与编辑任务 |
| [`agent-content-service`](agent-content-service/src/main) | 内容作品、摄影书与 Zine |
| [`agent-architecture-tests`](agent-architecture-tests/src/test) | 模块边界、契约、迁移归属与架构回归门禁 |

技术组合：**Spring Boot / Spring AI / Spring Cloud Alibaba**，MySQL、Redis、Nacos、Milvus，以及 Tika、BM25、RRF 和重排序。具体版本以各模块 POM 为准。

## 快速开始

### 1. 获取与构建

准备 **JDK 21、Maven 3.9 或更新版本**。

```sh
git clone https://github.com/bbb-520/Bobo4j.git
cd Bobo4j
mvn -B -Dmaven.compiler.fork=true -DskipTests package
```

仓库提供完整应用源码与自动化回归。以上命令只构建；开发时可执行 `mvn -B -Dmaven.compiler.fork=true verify` 运行测试和架构门禁。依赖隔离 MySQL、Milvus 或外部服务的测试保留环境门控，需单独准备对应测试环境。

### 2. 准备依赖与配置

按实际启用的功能准备服务，并向环境变量或私有配置中心注入敏感值。

| 依赖 | 用途 | 配置入口 |
| --- | --- | --- |
| MySQL、Redis、Nacos | 业务数据、缓存与服务配置/发现 | 各服务的 `src/main/resources/application.yml` |
| Milvus、Embedding 与 Rerank 模型 | 文档索引和检索 | RAG 服务配置及模型路由 |
| 对话、视觉与图片模型供应商 | 模型问答、识图和图片创作 | 账户服务的模型配置及对应服务配置 |
| OSS、邮箱、支付宝 | 资产、验证码与充值 | 对应领域服务配置；按需启用 |
| 服务间签名密钥 | 内网身份认证 | 各服务安全配置，保持密钥配置一致 |

数据库迁移保存在各服务的 `src/main/resources/db/migration`，由 Flyway 管理。运行前检查配置占位符及必填项，生产环境使用 `prod` 配置。

### 3. 启动服务与前端

各服务都有独立启动入口。例如，依赖与配置就绪后启动账户服务：

```sh
java -jar agent-auth-service/target/agent-auth-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

按部署配置启动其余服务及网关，再连接配套 [Bobo_web](https://github.com/bbb-520/Bobo_web)。领域服务与管理端口应位于内网。公开仓库不附带生产凭据和一键部署环境。

<details>
<summary><b>默认服务端口与内网拓扑</b></summary>

| 服务 | 默认端口 | 职责 |
| --- | ---: | --- |
| `agent-gateway` | 18000 | 浏览器 API 入口 |
| `agent-auth-service` | 18081 | 身份、权限与计费 |
| `agent-media-service` | 18082 | 图片资产与创作任务 |
| `agent-content-service` | 18083 | 内容作品 |
| `agent-orchestrator-service` | 18084 | Agent 与受控 MCP 派发 |
| `agent-chat-service` | 18085 | 对话与识图 |
| `agent-rag-service` | 18086 | 文档、检索与问答 |

```text
浏览器 → agent-gateway:18000
                ├─ agent-auth-service:18081
                ├─ agent-media-service:18082
                ├─ agent-content-service:18083
                ├─ agent-orchestrator-service:18084
                ├─ agent-chat-service:18085
                └─ agent-rag-service:18086
```

以上为源码默认端口，可由部署配置覆盖；生产环境只对外暴露网关入口。

</details>

<details>
<summary><b>主要 API 入口</b></summary>

| 入口 | 用途 |
| --- | --- |
| `/api/auth/**` | 登录、注册与账户会话 |
| `/api/chat` | 流式对话 |
| `/api/chat/image-intent` | 判断当前图片请求是新图创作、参考图编辑、图片问答或等待明确指令 |
| `/api/agent-executions/**` | 创建、查询、停止与恢复 Agent 任务 |
| `/api/documents/**` | 文档上传、处理进度、摘要与重试 |
| `/api/document-conversations/**` | 文档会话与问答 |
| `/api/billing/**` | 钱包、额度与消费记录 |

具体请求字段、权限与响应格式，以对应 Controller 和 `agent-api` 协议为准。

</details>

## Agent 如何使用工具

MCP 客户端位于独立的 [`agent-mcp`](agent-mcp/src/main) 库模块，随编排服务一起打包部署。编排服务通过适配器接入工具，继续管理决策、授权范围、执行账本、预算与回执；没有新增服务进程或端口。MCP 模块不依赖业务服务或数据库。

当前有 **5 个内置工具**，注册于 [`ExecutionToolRegistry`](agent-orchestrator-service/src/main/java/com/bbb/exercise/agentdemo/orchestrator/execution/ExecutionToolRegistry.java)。

| 工具 | 能做什么 | 调用边界 |
| --- | --- | --- |
| `rag.search` | 检索所选文档会话的证据 | 限定文档会话与用户身份 |
| `rag.answer` | 基于文档回答并校验答案 | 经过检索、引用和事实评估 |
| `rag.read_sections` | 读取已检索到的文档原文片段 | 限定已知来源和读取范围 |
| `visual_memory.search` | 检索当前用户的视觉记忆 | 限定用户与租户 |
| `media.create` | 创建图片生成任务 | 需要用户明确授权图片生成 |

模型输出工具选择与参数，服务端通过注册表和执行账本完成受控派发。工具不能自行接受模型提供的用户身份或绕过调用账本。

**MCP 支持首批 4 个可选的公开文档工具，源码默认关闭。** 编排服务支持后台连接、工具发现、完整参数校验、租户白名单与受控派发；工具输入定义按审核过的版本固定指纹。成功结果可重放，超时或停止后的未知调用不自动重发。

| 可选 MCP 工具 | 用途 | 数据范围 |
| --- | --- | --- |
| `mcp.learn.search` | 检索 Microsoft/Azure 官方技术文档 | 最多四条带来源链接的摘要 |
| `mcp.learn.fetch` | 读取 Microsoft Learn 文档页 | 限定官方 HTTPS 链接；长文明确标记节选 |
| `mcp.deepwiki.structure` | 查看开源仓库的文档目录 | DeepWiki 已收录的公开 `owner/repo` |
| `mcp.deepwiki.ask` | 查询开源仓库实现与使用方式 | 基于仓库资料生成的解释，需与官方原文区分 |

在 **AGENT 模式**提问，例如“检索 Azure Java 托管身份的官方说明”或“查阅 spring-projects/spring-ai，解释 ToolCallback 的用途”。模型根据已授权工具的说明选择工具及参数；服务端再次校验后调用。只有已启用、租户获授权且定义未变化的工具才会进入模型可用目录。普通 CHAT 和文档问答流程继续使用原有路径。

预设服务为 [Microsoft Learn](https://github.com/MicrosoftDocs/mcp) 和 [DeepWiki](https://docs.devin.ai/work-with-devin/deepwiki-mcp)，官方提供免密钥的公开数据访问。只发送简短公开技术查询，不发送用户文档、身份凭据或私有代码。这里只支持指定资料库的文档检索；全网搜索、私人 GitHub 账户及写入工具留待后续阶段。

运维通过 `app.mcp` 配置已审核的 Streamable HTTP 连接、精确租户列表、工具别名和 Schema 指纹；第一阶段只允许明确审核为只读且无外部收费的工具。模型选择工具仍经过平台执行账本。MCP 服务离线不会阻止普通聊天运行，远端返回的 Token 字段不作为模型计费依据。

启用首批预设需同时设置 `MCP_ENABLED=true`、`MCP_PUBLIC_DOCS_ENABLED=true` 和精确的 `MCP_PUBLIC_DOCS_TENANTS`，建议 `MCP_REQUEST_TIMEOUT=30s`。没有租户白名单不能启用；工具定义变化会停止放行，需重新审核。文档摘要保留链接，节选会标记 `truncated`；工具服务的免费访问不免除模型决策和回答本身的既有费用。

## 安全与边界

- **身份和权限在服务端校验。** 用户与租户范围由可信身份建立，领域服务通过内部签名协作。
- **模型输出需要检查。** 文档和图片内容作为待分析数据处理，答案仍须经过引用与事实评估；模型判断存在局限，重要结论应查看原文。
- **消费按实际回执核对。** 幂等重放沿用既有结果；无法确认供应商结果时保留核对状态。
- **停止有执行边界。** 已向供应商派发的请求可能继续执行，停止页面等待不代表撤销供应商调用。
- **源码与线上配置分开。** 密钥、数据库连接凭据、真实业务数据和服务器环境保持私有。

## 开发与维护

`main` 集中维护当前业务源码、测试、构建文件、通用维护脚本、README 与配图。未另行说明时，代码修改在主分支进行，完成验证后提交并推送 GitHub；服务器部署单独安排。真实环境配置、密钥、业务数据与本机验证产物保持私有，源码提交与实际服务器部署分别记录版本。

维护时重点关注：模型供应商连通性、真实用量回执、失败与核对状态、文档引用质量、数据库迁移兼容性，以及索引和数据库备份。修改模型或问答链路后，重新验证简历分析、长文档、表格、无证据问题、连续追问与幂等重放。

### 最近的改进

| 日期 | 改进 |
| --- | --- |
| 2026-10-18 | 完成 07 提示词收敛：公共护栏与任务规则集中维护，预算估算和供应商发送共用最终 system 组装；保留动态工具、带图聊天与图片证据规则，旧未完成步骤沿用原持久请求恢复 |
| 2026-10-18 | 跨日完成主分支整合验证：后端完整构建、架构门禁、维护脚本、配置与文档引用检查；保留本地恢复备份，统一后续默认在 main 修改并推送 |
| 2026-10-17 | 合并已发布优化分支与本地完整代码，统一主分支源码、测试、构建及维护入口；配套前端同步整合主题、会话恢复、提示词库与图片编辑路由 |
| 2026-10-17 | 会话历史按完整用户尝试选择，保留失败后的用户问题，排除未完成与孤立助手回复；两条 CHAT 路径共用预算、角色标记和当前问题渲染 |
| 2026-10-17 | 前端图片请求复用计费 CLASSIFY：区分新图、参考图编辑、识图和等待明确指令；补齐参考图保留、停止、账号切换、刷新重试与存储失败保护 |
| 2026-10-17 | 工具回执由 ContextAssembler 按完整条目裁剪，包装、说明与有界省略标记计入预算，保持 JSON 结构合法 |
| 2026-10-17 | 修复当前问题被作为自身历史再次追加的问题；重复问句仍保留，持久请求重入继续使用原输入快照与回执 |
| 2026-10-17 | 完成事实与验证基线核对、双聊天入口差异分析，以及 02–04 的候选发布准备；保留入口，共享策略，未执行服务器切换 |
| 2026-10-16 | 接入四个可选公开文档 MCP 工具，增加严格参数边界、来源链接及长结果节选 |
| 2026-10-16 | 增加默认关闭的 MCP 接入底座：后台连接、参数校验、租户隔离与持久回执 |
| 2026-10-16 | 细化无引用段落修复反馈，逐项校验列表与表格引用；保持独立事实支持评估 |
| 2026-10-15 | 模型请求使用独立连接，关闭传输层自动重发，减少闲置连接重置导致的附件失败 |

本日历史策略默认使用 `CHAT_HISTORY_TOKEN_LIMIT=96000` 个保守 UTF-8 字节，并同时保持最多 20 条有效消息、32000 个渲染字符；超预算时整轮及更早历史省略，不截断消息或 JSON。工具回执默认预算为 `EXECUTION_CONTEXT_TOKEN_LIMIT=6000` 个保守 UTF-8 字节。它们用于上下文选择，不代表供应商实测 Token 或结算金额。

此前主分支整合的完整 `clean verify` 共 758 项测试：734 项通过、24 项按环境门控跳过，失败与错误均为 0；其中架构门禁 56 项全部通过。Python 维护与评测工具共 50 项：49 项通过、1 项按环境跳过；Compose、Prometheus、Alertmanager 配置检查及文档本地链接检查通过。配套前端 19 组单元脚本、类型检查和生产构建通过。本次优化与整合没有调用真实收费模型或部署服务器，真实分类效果、延迟与成本评测仍待后续验证。

补充验证中，离线备份 Java 辅助工具的 11 项断言、固定上游资料的提示词导出 10 项测试全部通过；同时固定前端目录文件的 LF 换行，保证跨平台逐字节校验。配套前端的 9 项本地 mock 浏览器回归全部通过，覆盖聊天恢复、历史分页、提示词库及私有记忆隔离；真实模型、支付和线上环境仍未执行验收。

[07 提示词组装](docs/optimization-07.md)的后续验证：定向 21 项全部通过；完整 `clean verify` 共 767 项，743 项通过、24 项按环境门控跳过，零失败与错误，其中架构门禁 56 项通过。实际本地 HTTP 请求覆盖 84 种主备、模式和补充内容组合；旧文本、带图和决策步骤的六种恢复组合核对原请求与派发次数。预算仍为保守估算，未调整计费规则、执行真实模型评测或部署服务器。

欢迎通过 [Issues](https://github.com/bbb-520/Bobo4j/issues) 提交可复现问题或改进建议。请附上复现步骤、相关模块与脱敏错误信息，避免提交真实密钥或个人资料。

<p align="center"><sub>先找到证据，再给出答案。让每一步协作，都有迹可循。</sub></p>
