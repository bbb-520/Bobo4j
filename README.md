# BoboWorld4J Agent Platform

BoboWorld4J 是一个基于 Java 21 的多模型 Agent 平台，面向对话、视觉理解、图片生成、内容创作与任务编排场景。项目采用 Maven 多模块架构，以领域服务承载可独立部署的业务能力，同时保留 `agent-chat-legacy` 作为迁移期兼容与回滚路径。

## 项目定位

- 统一入口：通过 `agent-gateway` 暴露稳定的 `/api/**` API，并使用 Nacos 做服务发现与配置管理。
- 领域拆分：认证、聊天、媒体、内容和 Agent 编排分别由独立服务负责。
- 持久化优先：MySQL 保存业务事实，Flyway 管理服务级 schema 演进，Redis 只承担聊天记忆和短期状态。
- 异步可靠：图片生成采用数据库任务队列、租约抢占、幂等终态和 OSS 结果引用。
- 兼容迁移：旧单体完整归属 `agent-chat-legacy`，新服务可独立构建、测试和打包。

## 顶层架构

```text
Client
  │ HTTP / SSE
  ▼
agent-gateway:18000 ── Nacos discovery/config
  ├── agent-auth-service:18081
  ├── agent-chat-service:18085
  ├── agent-media-service:18082
  ├── agent-content-service:18083
  └── agent-orchestrator-service:18084
          │
          ├── MySQL 8.4  （业务事实与任务状态）
          ├── Redis Stack Server 7.4  （聊天记忆、RediSearch 与短期状态）
          └── Aliyun OSS （源图、生成结果与签名 URL）
```

### Maven 模块分层

```text
BoboWorld4J/                         纯 Maven 聚合根：只编排 modules
├── platform-parent/                 公共父 POM、Java/BOM/版本管理
├── agent-common/                    公共响应、配置和架构契约
├── agent-api/                       跨服务 API/DTO 契约
├── agent-gateway/                   Gateway、路由、服务发现和入口过滤
├── agent-auth-service/              注册、登录、会话、用户模型配置
├── agent-chat-service/              对话、SSE、会话查询和聊天记忆
├── agent-media-service/             图片资产、生成任务、OSS 和 Worker
├── agent-content-service/           Bobo's World、照片和 Zine 内容
├── agent-orchestrator-service/      AgentRun 状态机、编排和恢复
└── agent-chat-legacy/               旧单体兼容壳与回滚应用
```

根模块 `BoboWorld4J` 不拥有任何 Java、测试或运行资源，根目录不存在 `src`。所有旧单体代码、配置、静态资源、SQL 和测试均位于 `agent-chat-legacy/src`，Legacy 使用标准 Maven 目录和自身显式依赖，不再通过 `../src` 编译。

## 领域服务

| 模块 | 默认端口 | 核心职责 | 主要持久化事实 |
| --- | ---: | --- | --- |
| `agent-gateway` | 18000 | 统一入口、路由、服务发现、请求过滤 | 无业务表 |
| `agent-auth-service` | 18081 | 注册、登录、会话、API Key、模型配置 | `app_user`、`auth_session`、`user_api_key`、`user_model_profile` |
| `agent-chat-service` | 18085 | 多模型对话、视觉消息、SSE、会话与消息 | `chat_conversation`、`chat_message`、`vision_memory` |
| `agent-media-service` | 18082 | 图片上传、生成任务、Worker、OSS | `image_asset`、`image_job`、租约字段 |
| `agent-content-service` | 18083 | Bobo's World、照片、Zine 内容 | 内容域表及共享兼容基线 |
| `agent-orchestrator-service` | 18084 | AgentRun、任务编排、状态转移、恢复 | `agent_run` |
| `agent-chat-legacy` | 18080 | 迁移期旧单体兼容与回滚 | 旧单体兼容表 |

## 技术栈

- 语言与构建：Java 21、Maven 3.9+、Spring Boot 4.1.1。
- Web 与服务治理：Spring WebFlux、Spring Cloud Gateway、Spring Cloud LoadBalancer、Nacos 2025.1.0.0、Sentinel。
- Agent 与模型：Spring AI 2.0.1、OpenAI-compatible Provider、DashScope 适配、Auth-owned 模型配置。
- 数据访问：MyBatis-Plus 3.5.16、Spring JDBC、MySQL 8.4、Flyway。
- 状态与对象存储：Redis Stack Server 7.4（含 RediSearch）、Aliyun OSS。
- 测试：JUnit 5、AssertJ、Mockito、Spring Boot Test，覆盖单元、API 契约、持久化和 schema 边界。
- 交付：Docker Compose、多服务镜像、Nacos 配置导入和 PowerShell 冒烟脚本。

## 持久化与一致性

每个可持久化服务在自身的 `src/main/resources/db/migration` 中维护 Flyway 版本化迁移。服务启动时执行迁移，Docker MySQL 不再挂载重复初始化脚本。

- Auth：`V1__auth_baseline`。
- Chat：`V1__chat_baseline`。
- Media：`V1__media_baseline`、`V2__media_lease_idempotency`。
- Content：`V1__content_baseline`、`V2__publish_prompt_snapshot`。
- Orchestrator：`V1__orchestrator_baseline`。
- Legacy：仅保留 `db/migration/V2__image_job_provider_model.sql` 兼容脚本；Legacy 默认只读，不参与新服务 schema 初始化。

关键状态规则：

- 图片任务状态为 `QUEUED → PROCESSING → SUCCEEDED/FAILED`。
- Worker 使用 `lease_owner` 与 `lease_until` 条件更新抢占任务，终态更新必须带状态条件。
- 会话和消息先落 MySQL，再通过 SSE 返回；Redis 不替代业务事实表。
- 迁移期共享逻辑库只使用业务标识关联，不新增跨服务外键；长期目标是通过 HTTP/SSE 服务 API 解耦。
- 生产迁移前必须备份 MySQL、Nacos 和 OSS 关键元数据；应用回滚不能直接删除 Flyway 记录。

## 主要 API

| 方法 | 路径 | 领域 |
| --- | --- | --- |
| `POST` | `/api/auth/register`、`/api/auth/login` | 认证 |
| `GET` | `/api/auth/me` | 当前用户 |
| `GET/POST/DELETE` | `/api/settings/**` | 用户模型与视觉记忆配置 |
| `POST` | `/api/chat` | 对话、视觉理解、继续指令、SSE |
| `GET` | `/api/settings/visual-memory/context` | 视觉记忆检索 |
| `POST` | `/api/image-assets/upload-policy` | 图片上传策略 |
| `POST` | `/api/image-assets/{assetId}/complete` | 图片上传完成确认 |
| `GET/POST` | `/api/image-jobs/**` | 图片生成任务 |
| `GET/POST` | `/api/bobo/**` | Bobo's World 内容域 |
| `POST` | `/api/zine/generate` | Zine 图片生成 |
| `GET` | `/actuator/health` | 服务健康检查 |

字段、鉴权和错误码以对应 Controller、DTO 与 API 契约为准。真实模型 Key、OSS 密钥、数据库密码和 Nacos Token 只能通过环境变量或 Secret 注入。

## 本地开发

### 前置条件

- JDK 21。
- Maven 3.9+。
- Docker Desktop / Docker Compose（仅完整容器联调需要）。
- 可选：模型 Provider 和 Aliyun OSS 凭证。

Windows 如 Maven 未加入 PATH，可使用：

```powershell
.\scripts\ensure-maven.ps1
```

如果使用仓库提供的本地 Maven 缓存配置：

```powershell
& 'D:\java\apache-maven-3.9.9-bin\apache-maven-3.9.9\bin\mvn.cmd' `
  --% -s D:\path\to\repo\.m2repo\settings.xml test
```

### 构建与测试

```powershell
# 全量测试
mvn test

# 全量打包
mvn -DskipTests package

# 只验证 Legacy 及其父模块
mvn -pl agent-chat-legacy -am test

# 验证根模块边界契约
mvn -pl agent-common -Dtest=RootAggregatorContractTest test
```

### Docker Compose

```powershell
Copy-Item .env.example .env
docker compose -f infra/docker-compose.yml up -d
# Nacos 3.1 首次启动需要初始化本地开发管理员（仅首次执行）
Invoke-RestMethod -Method Post `
  -Uri 'http://localhost:8848/nacos/v3/auth/user/admin' `
  -Body @{ username = 'nacos'; password = 'nacos' }
.\scripts\import-nacos.ps1
mvn -DskipTests package
docker compose -f infra/docker-compose.yml -f docker-compose.app.yml up -d --build
.\scripts\smoke-test.ps1
```

基础设施默认地址：Nacos `8848`、Sentinel Dashboard `8080`、MySQL `3306`、Redis Stack Server `6379`；应用统一入口为 Gateway `18000`。

`agent-chat-service` 使用 Spring AI Redis Chat Memory，需要 Redis Stack Server 提供 RediSearch 命令；不要将其替换为普通 `redis:7.4-alpine`。`.env.example` 只包含本地开发占位值，部署前必须替换密码、Token、模型 Key 和 OSS 凭证。Windows 当前 Docker Desktop 安装在 `D:\docker\DockerDesktop` 时，若当前终端尚未刷新 PATH，可直接使用 `D:\docker\DockerDesktop\resources\cli-plugins\docker-compose.exe`。

### Docker 与 IDEA 本地运行模式

Docker 完整模式和 IDEA 本地模式不要同时启动 6 个业务服务，否则会争抢 `18000`、`18081`—`18085` 端口。

完整 Docker 模式：

```powershell
docker compose -f infra/docker-compose.yml -f docker-compose.app.yml up -d --build
.\scripts\smoke-test.ps1
```

IDEA 本地模式：保留 MySQL、Nacos、Redis Stack、Sentinel 基础设施运行，先停止 Docker 业务容器，再启动 IDEA 中的各服务：

```powershell
docker compose -f infra/docker-compose.yml -f docker-compose.app.yml stop agent-gateway agent-auth-service agent-chat-service agent-media-service agent-content-service agent-orchestrator-service
```

本地服务默认使用 Nacos `127.0.0.1:8848`、账号 `nacos/nacos`，Chat Redis 密码默认使用 `change-redis-me`；Docker Compose 会通过环境变量覆盖为容器内地址 `nacos:8848` 和 `redis`。

## 部署与回滚

推荐拓扑为 Linux + Docker Engine 24+ / Compose v2+，外置或托管 MySQL、Redis、OSS 和 Nacos 集群，公网只暴露 Gateway 或其前置负载均衡。

部署顺序：

1. 准备并替换 `.env` 中全部默认密码、Token 和密钥。
2. 启动 MySQL、Redis、Nacos、Sentinel 等基础设施。
3. 构建镜像并执行 `docker compose ... config --quiet`。
4. 先更新领域服务，再更新 Gateway，执行健康检查、登录、SSE、图片任务和 AgentRun 冒烟。
5. 失败时恢复上一版本镜像；若新版本 schema 不兼容，按备份恢复数据库并暂停 Worker。

迁移期也可以启动 `agent-chat-legacy` 作为兼容回滚应用。Legacy 不作为新 Compose 的主业务容器，但保留其独立可执行 jar 和原有 API、配置、数据库兼容性。

## 验收状态

截至 2026-09-30：

- Maven reactor：11 个模块（根聚合、父 POM、Legacy、Common、API、Gateway、Auth、Chat、Media、Content、Orchestrator）。
- 全量测试：以当前 Maven reactor 实际发现结果为准；基础设施 Testcontainers 测试只有在 `RUN_INFRASTRUCTURE_TESTS=true` 时启用。
- 根聚合契约：根 `src` 不存在、Legacy 标准源码/资源目录存在、Legacy POM 不含 `../src`。
- Legacy 回归：22/22 测试通过，标准目录编译和资源复制通过。
- 全量打包：以 `mvn -DskipTests package` 为准复核可执行 jar 产物。
- Docker Compose 配置：通过 `config --quiet`。
- Docker 镜像：6 个应用镜像使用最新 JAR 重建成功；MySQL、Nacos、Redis Stack Server、Sentinel Dashboard 基础设施保持健康。
- Nacos 配置：6 个服务配置导入成功，Nacos 3.1 本地管理员初始化成功。
- Docker 全量冒烟：`scripts/smoke-test.ps1` 通过，Nacos 与 Gateway、Auth、Chat、Media、Content、Orchestrator 健康端点全部返回 200。
- 本地运行验收：Content 服务使用最新 JAR 启动成功，Nacos 配置加载成功，`/actuator/health` 返回 HTTP 200；验收后已停止 Docker 业务容器以释放 IDEA 端口。

架构规格和实施计划位于 `docs/superpowers/specs/` 和 `docs/superpowers/plans/`；不再引用已移除的 `docs/verification/`。

## 目录速览

```text
agent-gateway/                 API 网关
agent-auth-service/            认证与用户模型配置
agent-chat-service/            对话、会话、SSE、记忆
agent-media-service/           图片资产、任务和 Worker
agent-content-service/         Bobo's World、照片、Zine
agent-orchestrator-service/    AgentRun 状态机
agent-chat-legacy/             旧单体回滚壳，唯一拥有旧单体代码
agent-common/ agent-api/       公共基础设施和 API 契约
platform-parent/               公共父 POM 与版本管理
infra/                         Nacos、MySQL、Redis、Sentinel
scripts/                       构建、配置导入、镜像和冒烟脚本
```
