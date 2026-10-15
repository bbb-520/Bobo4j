# BoboWorld4J（BOBO4J）

基于 Java 21 与 Spring AI 的 AI 应用平台后端，提供流式对话、文档问答、Agent 执行、图片创作和账户计费。

配套前端：[Bobo_web](https://github.com/bbb-520/Bobo_web)。本仓库展示核心业务源码、数据库迁移、应用配置和 Maven 构建定义。测试、运维资料、部署脚本及环境密钥在私有工作区维护。

## 核心能力

- 对话：流式回答、图片理解、会话历史、用户及租户隔离。
- 文档：Tika 文件解析、语义切片、Milvus 索引、Dense/BM25 混合检索、重排序、摘要及带引用的问答。
- Agent：持久化多步任务、工具白名单、预算约束、停止与恢复、事件回放。
- 创作：图片生成与编辑、图片资产、Zine 和 Bobo World 作品。
- 账户：邮箱验证码、登录、免费 Token、钱包、消费记录、支付宝支付、RBAC 与审计。
- 模型调用：主备模型、真实用量回执、幂等结算、结果不明时保留核对状态。

## 模块

| 模块 | 职责 |
| --- | --- |
| `platform-parent` | 统一依赖及构建配置 |
| `agent-api` | 跨服务协议、DTO、模型能力定义 |
| `agent-common` | 公共响应、身份签名及基础配置 |
| `agent-runtime` | 模型调用、内部服务客户端、身份认证、对象存储 |
| `agent-gateway` | 请求路由、访问控制和流量管理 |
| `agent-auth-service` | 账户、权限、计费、模型配置和支付 |
| `agent-chat-service` | 对话、图片理解及会话记忆 |
| `agent-rag-service` | 文档入库、索引、检索、摘要和问答 |
| `agent-orchestrator-service` | 持久化 Agent 执行与工具协调 |
| `agent-media-service` | 图片资产、生成及编辑任务 |
| `agent-content-service` | 内容作品、摄影书及 Zine |

## 构建

需要 JDK 21 与 Maven 3.9。各模块的依赖版本以 `pom.xml` 为准。

```sh
mvn -B -Dmaven.compiler.fork=true -DskipTests package
```

服务配置位于各模块的 `src/main/resources/application.yml`；数据库迁移位于 `src/main/resources/db/migration`。运行前按这些配置提供 MySQL、Redis、Nacos、Milvus，以及实际使用的模型供应商、OSS、邮箱或支付配置。敏感值通过环境变量或私有配置中心注入，不写入仓库。

平台各服务有独立启动入口，网关面向浏览器，领域服务和管理端口在内网运行。生产环境启用 `prod` 配置，并配置服务间签名密钥。公开源码不包含服务器环境、账户凭据或部署产物。

## 主要接口

| 入口 | 用途 |
| --- | --- |
| `/api/auth/**` | 登录、注册与账户会话 |
| `/api/chat` | 流式对话 |
| `/api/agent-executions/**` | 创建、查询、停止及恢复 Agent 任务 |
| `/api/documents/**` | 文档上传、处理进度、摘要及重试 |
| `/api/document-conversations/**` | 文档会话与引用 |
| `/api/billing/**` | 钱包、额度和消费记录 |

具体请求字段、权限和响应格式以对应 Controller 及 `agent-api` 协议为准。

## 模型连接修复

2026-10-15：模型请求使用独立连接，避免闲置连接被供应商重置后导致附件处理失败；关闭传输层自动重发，确保请求次数与持久化计费尝试一致。无法确认供应商结果的请求继续保留核对状态，避免伪造成功或重复结算。
