# infra 基础设施帮助文档

## 1. 目录作用

本目录提供后端依赖的 Docker Compose、Nacos 配置、Milvus 单机配置和 Prometheus 监控规则，不是 Maven 模块。应用服务定义在根目录 [docker-compose.app.yml](../docker-compose.app.yml)，镜像模板为 [Dockerfile.service](../Dockerfile.service)。

## 2. 基础设施组成

| 服务 | 当前镜像 | 主机端口 | 用途 |
| --- | --- | --- | --- |
| Nacos | `nacos/nacos-server:v3.1.1` | 8848、9848 | 配置与注册发现 |
| Sentinel Dashboard | `bladex/sentinel-dashboard:1.8.8` | 8080 → 8858 | 流控规则与监控面板 |
| MySQL | `mysql:8.4` | 3306 | 各服务拥有的业务表与迁移历史 |
| Redis Stack | `redis/redis-stack-server:7.4.0-v8` | 6379 | 聊天记忆基础设施、模型熔断共享状态 |
| Milvus | `milvusdb/milvus:v2.6.2` | 回环 19530、9091 | 向量索引与健康 / 指标 |
| Prometheus | `prom/prometheus:v3.4.2` | 回环 19090 → 9090 | 抓取应用 / Milvus 指标与告警规则 |
| Zipkin | `openzipkin/zipkin:3.5.1` | 回环 9411 | 调用链 |

具体端口绑定和容器名见 [docker-compose.yml](docker-compose.yml)。Nacos 3 控制台内部端口 8080 当前没有在本基础设施文件映射；主机 8080 是 Sentinel，不能把它当作 Nacos 控制台。

MySQL、Redis、Milvus、Nacos 和 Prometheus 使用命名卷。Milvus 使用嵌入式 etcd 和本地存储，配置见 [embedded-etcd.yml](milvus/embedded-etcd.yml)，不需要另外启动一套外部 etcd / MinIO。

## 3. 配置从哪里来

1. 各服务 `src/main/resources/application.yml` 提供默认值和环境变量映射。
2. `infra/nacos/import/*.yml` 是可导入的远端配置素材，Data ID 为对应文件名，配置 group 为 `AGENT_GROUP`。
3. 根目录 Compose 把需要的变量传入容器，配置容器内 Auth / Chat / RAG / Media URL。
4. 本地 `.env` 由 Compose 做变量插值；普通 Java、PowerShell 脚本不会自动读取它。

必须分别检查 `.env → Compose environment → Spring 属性 → Nacos 覆盖`，仅向 `.env` 添加一个新变量不保证服务能收到。配置变化后容器需重新创建，单纯 restart 不重新读取环境。

关键配置分组：

- `MYSQL_*`、`REDIS_PASSWORD`、`NACOS_*`：基础设施认证和连接。
- `INTERNAL_PRINCIPAL_SECRETS`、`INTERNAL_PRINCIPAL_ACTIVE_KEY_ID`：所有服务一致的内部签名密钥环。
- `OPENAI_API_KEY`、`QWEN_API_KEY`、`PLATFORM_*`：Auth 管理的平台模型配置，Rerank 还需实际区域 / 工作空间 URL。
- `SMTP_*`、`AUTH_OTP_SECRET`：QQ 邮箱验证码；注册流程需要可用邮件服务。
- `OSS_ENABLED`、`ALIYUN_OSS_*`：图片资产、图片 Worker 与作品发布。
- `PAYMENT_NOTIFY_BASE_URL`、`ALIPAY_*`、`WECHAT_*`：可选支付渠道；私钥文件按 Compose 挂载到 Auth。

不把真实配置写入本目录可公开文件。默认示例密码和加密配置用于说明参数，部署时应使用实际配置。内部签名密钥格式见 [Common](../agent-common/README.md)。

## 4. 只启动基础设施

在仓库根目录准备本地 `.env` 后执行：

```powershell
docker compose --env-file .env -f infra/docker-compose.yml config --quiet
docker compose --env-file .env -f infra/docker-compose.yml up -d
docker compose --env-file .env -f infra/docker-compose.yml ps
```

这套方式适合本机 JVM 运行服务。使用 `MYSQL_HOST=127.0.0.1`、`REDIS_HOST=127.0.0.1`、`NACOS_SERVER_ADDR=127.0.0.1:8848`、`MILVUS_URI=http://127.0.0.1:19530`，将环境变量放到各启动终端 / IDE Run Configuration。

应用容器则使用 `mysql`、`redis`、`nacos`、`milvus` 等 Docker 服务名；容器内 `localhost` 指向该容器自己。Nacos 默认开启鉴权，应用用户名 / 密码须对应实际 Nacos 账户，并保证注册 group 一致。Auth 的本地 YAML 没有像部分服务一样显式声明 discovery group，本机启动可统一设置 `SPRING_CLOUD_NACOS_DISCOVERY_GROUP=AGENT_GROUP` 和 `SPRING_CLOUD_NACOS_CONFIG_GROUP=AGENT_GROUP`；应用 Compose 已设置。

## 5. 组合基础设施和应用

多个 `-f` 合并时，相对路径按基础项目目录解析。现有 [build-images.ps1](../scripts/build-images.ps1) 将 infra 文件放在首位，配置展开后应用 build context 会变成 `infra`，而 Dockerfile 和 JAR 在仓库根目录。**不能把该脚本的原命令当成已经验证可构建全部镜像的命令。**

可在仓库根目录保存以下 `compose.server.local.yml` 作为本地 Compose 入口（该文件名已被 `.gitignore` 排除），使用支持 `include` 的 Compose：

```yaml
name: agentdemo
include:
  - path: ./infra/docker-compose.yml
  - path: ./docker-compose.app.yml
```

include 保留各被包含文件的路径基准：应用 build context 指向根目录，Milvus / Prometheus 挂载指向 infra 下的文件。该入口有 14 个后端及基础设施服务，不包含独立前端仓库。

```powershell
# 根目录；Dockerfile 只复制现成 JAR，因此先构建并验证 Java
mvn '-Dmaven.compiler.fork=true' verify
if ($LASTEXITCODE -ne 0) { throw 'Maven verification failed' }

docker compose --env-file .env -f compose.server.local.yml config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Compose validation failed' }

docker compose --env-file .env -f compose.server.local.yml build
if ($LASTEXITCODE -ne 0) { throw 'Image build failed' }

docker compose --env-file .env -f compose.server.local.yml up -d
docker compose --env-file .env -f compose.server.local.yml ps
```

上例中的 include 结构已通过只读配置展开检查路径；实际镜像构建、健康状态、模型、邮件和支付仍需在目标环境验证。后续操作保持同一入口文件和 project name，避免创建互不相连的第二套服务 / 数据卷。

## 6. Java 服务与数据库迁移

本机 JVM 建议先 Auth，再 Media / Chat，随后 Content / RAG / Orchestrator，最后 Gateway；每个 JAR 使用独立终端。具体命令见各模块 README。

所有服务可共用一个 MySQL schema，但各自拥有迁移文件和历史表：

| 服务 | Flyway 历史表 |
| --- | --- |
| Auth | `flyway_schema_history_auth` |
| Chat | `flyway_schema_history_chat` |
| RAG | `flyway_schema_history_rag` |
| Media | `flyway_schema_history_media` |
| Content | `flyway_schema_history_content` |
| Orchestrator | `flyway_schema_history_orchestrator` |

Gateway 和共享库无业务迁移。应用 Compose 设置 `baseline-on-migrate=true`、`baseline-version=0`，让新的服务历史仍执行 V1；对已有库采用同样配置前应先核对结构和迁移历史，不把历史表改名当作升级方案。RAG 文档目录另用 `rag-documents` 卷持久化，不在 MySQL 备份中。

## 7. Nacos 配置目录

[nacos/import](nacos/import) 包含 7 个服务配置，文件名与应用 import 的 Data ID 对应。导入脚本通过配置发布 API 写入同名条目，可能覆盖已有远端值；导入前比较配置，避免旧模板覆盖当前部署参数。

服务注册发现和配置导入是两个配置面。Nacos import 的 `group=AGENT_GROUP` 不自动保证所有服务的 discovery group 相同。检查 Gateway 的路由目标服务名、各应用 name 和 Nacos 实际实例列表。

## 8. 监控和调用链

[prometheus.yml](prometheus/prometheus.yml) 每 15 秒抓取 7 个服务的 `/actuator/prometheus`，并抓取 Milvus `/metrics`。目标使用容器服务名；若业务改为本机 JVM，需改为容器能访问的宿主机地址。

[alerts.yml](prometheus/alerts.yml) 定义备用模型持续失败、工具失败率大于 20%、RAG 验证不可用等规则。当前没有定义 Alertmanager 服务，规则触发不代表已经配置邮件 / 消息通知。

Zipkin 地址由应用配置传入，当前 Compose 默认采样 0.1；各服务本地 YAML 默认值可能不同。指标观察聚合行为，trace 帮助定位跨服务延迟；二者不能代替业务回执和账务记录。

## 9. 排错与数据保留

- **Gateway 503**：先查 Nacos 实例和 group，再查目标服务健康及容器地址。
- **镜像找不到 Dockerfile / JAR**：检查有效 build context，而不是仅检查文件是否存在于当前终端目录。
- **挂载路径不对**：检查 Compose 展开后的 source；不要通过切换文件顺序修好一处却破坏另一处。
- **改密码后数据库无法连接**：已有 MySQL 卷不会因环境变量自动修改数据库账户密码。
- **修改配置未生效**：用原入口执行 `up -d --force-recreate <服务名>`，检查 Nacos 是否覆盖。
- **图片任务不跑**：核对 OSS，Media Worker 在未配置 OSS 时跳过领取。
- **只通过健康检查**：还需分别验证注册 / 登录、聊天、文档、图片和支付中已配置的业务链路。
- **支付宝在 .env 开启但不可用**：运行 `./scripts/verify-payments.ps1 -ExpectedAppId 2021007109601042` 核对原 Compose 入口、显式环境文件和容器实际值；使用同一入口重建 Auth。详细验收见 [支付宝支付联调](../docs/alipay-payment-integration.md)。

升级前备份数据库、RAG 文件卷及相关对象存储资产。停止或重建容器不要求删数据卷；`down -v` 会删除该 Compose 项目的命名卷，不能作为常规排错步骤。

脚本行为和限制见 [scripts](../scripts/README.md)，模块文档入口见 [项目 README](../README.md)。
