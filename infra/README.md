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
| Alertmanager | `prom/alertmanager:v0.33.1` | 回环 19093 → 9093 | 告警分组；默认无外部通知 |
| Zipkin | `openzipkin/zipkin:3.5.1` | 回环 9411 | 调用链 |

具体端口绑定和容器名见 [docker-compose.yml](docker-compose.yml)。Nacos 3 控制台内部端口 8080 当前没有在本基础设施文件映射；主机 8080 是 Sentinel，不能把它当作 Nacos 控制台。

所有基础设施主机端口默认只绑定 `127.0.0.1`；确需私网访问时设置 `INFRA_BIND_ADDRESS` 为明确的私网网卡地址。Gateway 同样默认回环，可单独设置 `GATEWAY_BIND_ADDRESS`。应用容器之间通过 Docker 网络访问服务名，不依赖主机端口。

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
- `PAYMENT_NOTIFY_BASE_URL`、`ALIPAY_*`：支付宝支付；私钥文件按 Compose 挂载到 Auth。

不把真实配置写入本目录可公开文件。默认示例密码和加密配置用于说明参数，部署时应使用实际配置。内部签名密钥格式见 [Common](../agent-common/README.md)。

根目录 [.env.example](../.env.example) 是手工编写的公开本地模板，不包含真实部署凭据。复制为 `.env` 后私下修改；不要覆盖已有 `.env`。可选 [model-routing.env.example](../config/model-routing.env.example) 仅包含模型路由与超时设置，通过第二个 `--env-file` 加载，后面的环境文件优先。

本地默认 `APP_ACTIVE_PROFILES=local`。生产部署显式设为 `production`（或 `prod`），启动校验会拒绝公开默认内部签名密钥、活动 key 不匹配、默认数据库 / Redis / Nacos 密码及不安全的 Gateway CORS。所有服务使用相同的随机内部签名密钥环；Auth 还需安全的 API 加密密钥及至少 32 字符的 OTP secret。使用 `API_KEY_ENCRYPTION_KEYS` 轮换密钥时，仍需把旧的 `API_KEY_ENCRYPTION_KEY` 设为空或安全值，因为 v1 解密仍可能使用旧密钥。Auth 的 Compose 映射保留显式空值，允许安全密钥环配合 `API_KEY_ENCRYPTION_KEY=`；若已有 v1 密文，则保留其安全旧密钥直到迁移完成。Gateway 的 CORS 必须为明确的 HTTPS origin，不能包含通配符或 localhost；HTTPS 部署设置 `FORCE_COOKIE_SECURE=true`。生产校验通过不代表外部模型、邮件、支付配置已经完成验收。

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

统一使用已提交的 [compose.yml](../compose.yml)，要求 Docker Compose 2.20 或更高版本。[include](https://docs.docker.com/reference/compose-file/include/) 保留各文件的路径基准：7 个应用的 build context 指向根目录，Milvus / Prometheus 挂载指向 infra 下的文件。入口提供 15 个应用及基础设施服务。不要恢复 `-f infra/docker-compose.yml -f docker-compose.app.yml`，其共同路径基准会令应用构建目录错误地指向 infra。

include 明确以公开 `.env.example` 作为子项目默认值，根入口的 `--env-file .env` 和进程环境仍优先；这样不依赖子目录中未记录的私有 `.env`。配置检查使用 `config --quiet`，避免输出插值后的凭据。

```powershell
# 根目录；Dockerfile 只复制现成 JAR，因此先构建并验证 Java
./mvnw.cmd '-Dmaven.compiler.fork=true' verify
if ($LASTEXITCODE -ne 0) { throw 'Maven verification failed' }

docker compose --env-file .env -f compose.yml config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Compose validation failed' }

docker compose --env-file .env -f compose.yml build
if ($LASTEXITCODE -ne 0) { throw 'Image build failed' }

docker compose --env-file .env -f compose.yml up -d
docker compose --env-file .env -f compose.yml ps
```

Linux / macOS 将 Maven 命令改为 `./mvnw`。官方 [Maven Wrapper](https://maven.apache.org/tools/wrapper/) 3.3.4 的 only-script 发行脚本固定下载 Maven 3.9.9，分发包使用 SHA-256 校验。第一次运行需要网络和 JDK 21。CI 使用同一 wrapper；Windows [build-images.ps1](../scripts/build-images.ps1) 会先静默验证 Compose，再运行 Maven verify 和镜像构建。

配置展开不需要 Docker daemon；实际镜像构建、容器健康、模型、邮件和支付仍需在目标环境验证。后续操作保持同一入口文件和 project name，已有部署需沿用原项目名，避免创建第二套数据卷。

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

[alerts.yml](prometheus/alerts.yml) 和 [operations-alerts.yml](prometheus/operations-alerts.yml) 均只读挂载；Prometheus 将告警发送到 `alertmanager:9093`。Alertmanager 默认加载 [本地配置](alertmanager/alertmanager.yml)，其 `local-noop` receiver 不发送任何外部通知，启动无需 SMTP 或私有文件。告警可在 Prometheus / Alertmanager 界面查看，默认不保证通知投递。

需要邮件通知时，将 [SMTP 示例](../ops/monitoring/alertmanager.yml.example) 复制到仓库之外的私有目录，填写发送者和接收者，把密码单独保存为 secrets 目录下的 `smtp-password`，并限制宿主机文件权限。通过 `.env` 的 `ALERTMANAGER_CONFIG_FILE`、`ALERTMANAGER_SECRETS_DIR` 指定**绝对宿主机路径**；容器中的密码路径固定为 `/run/secrets/smtp-password`。确保容器运行用户可读取配置及密码，但不要开放给其他宿主机用户。真实配置启用前运行同版本校验：

```powershell
docker compose --env-file .env -f compose.yml config --quiet
docker compose --env-file .env -f compose.yml run --rm --no-deps --entrypoint /bin/amtool alertmanager check-config /etc/alertmanager/alertmanager.yml
# 通过后重建；使用匹配版本的 promtool 校验两组规则
docker compose --env-file .env -f compose.yml run --rm --no-deps --entrypoint /bin/promtool prometheus check config /etc/prometheus/prometheus.yml
docker compose --env-file .env -f compose.yml up -d alertmanager prometheus
```

私有运维 exporter 默认关闭：[operations-targets.yml](prometheus/operations-targets.yml) 为合法空目标列表。需要部署时遵循 [ops/monitoring](../ops/monitoring/README.md)，复制 [目标示例](prometheus/operations-targets.yml.example) 到仓库之外，再设置 `OPERATIONS_TARGETS_FILE` 为绝对路径。Prometheus 的 `host.docker.internal` 默认映射到 `host-gateway`；exporter 若仅绑定应用 bridge gateway，需把 `OPERATIONS_HOST_GATEWAY` 明确设为该网关 IP，两者可能不同。配置检查通过后，另外验证抓取成功、告警触发、通知发送计数和收件箱投递；不要只看 readiness。

Zipkin 地址由应用配置传入，当前 Compose 默认采样 0.1；各服务本地 YAML 默认值可能不同。指标观察聚合行为，trace 帮助定位跨服务延迟；二者不能代替业务回执和账务记录。

## 9. 排错与数据保留

- **Gateway 503**：先查 Nacos 实例和 group，再查目标服务健康及容器地址。
- **镜像找不到 Dockerfile / JAR**：检查有效 build context，而不是仅检查文件是否存在于当前终端目录。
- **挂载路径不对**：检查 Compose 展开后的 source；不要通过切换文件顺序修好一处却破坏另一处。
- **改密码后数据库无法连接**：已有 MySQL 卷不会因环境变量自动修改数据库账户密码。
- **修改配置未生效**：用原入口执行 `up -d --force-recreate <服务名>`，检查 Nacos 是否覆盖。
- **图片任务不跑**：核对 OSS，Media Worker 在未配置 OSS 时跳过领取。
- **只通过健康检查**：还需分别验证注册 / 登录、聊天、文档、图片和支付中已配置的业务链路。
- **支付宝在 .env 开启但不可用**：运行 `./scripts/verify-payments.ps1 -ExpectedAppId YOUR_ALIPAY_APP_ID` 核对原 Compose 入口、显式环境文件和容器实际值；使用同一入口重建 Auth。支付事务与回执处理见 [支付服务实现](../agent-auth-service/src/main/java/com/bbb/exercise/agentdemo/auth/billing/PaymentService.java)。

升级前备份数据库、RAG 文件卷及相关对象存储资产。停止或重建容器不要求删数据卷；`down -v` 会删除该 Compose 项目的命名卷，不能作为常规排错步骤。

脚本行为和限制见 [scripts](../scripts/README.md)，模块文档入口见 [项目 README](../README.md)。
