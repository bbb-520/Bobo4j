# scripts 辅助脚本帮助文档

## 1. 目录作用和运行约定

本目录保存 PowerShell 构建、配置导入、基础健康检查和历史迁移验证脚本，不是 Maven 模块。构建和迁移脚本通过 `$PSScriptRoot` 定位根目录；导入等脚本仍按各自说明从仓库根目录运行。

脚本统一使用 `$ErrorActionPreference='Stop'`。调用 Maven / Docker 的脚本还检查 `$LASTEXITCODE`，失败后抛异常停止；这不意味着脚本包含业务验证或所有部署参数。

## 2. 脚本总览

| 脚本 | 输入 / 依赖 | 主要效果 |
| --- | --- | --- |
| [build-images.ps1](build-images.ps1) | JDK 21、Maven Wrapper 首次下载网络、Docker Compose >=2.20 | 静默校验配置，执行 Maven verify，再构建应用镜像 |
| [import-nacos.ps1](import-nacos.ps1) | 可达 Nacos、进程环境中的账号和地址 | 发布 7 个服务的 YAML 配置 |
| [smoke-test.ps1](smoke-test.ps1) | 本机固定端口已运行服务 | 检查一组健康 URL 是否 HTTP 200 |
| [verify-account-migrations.ps1](verify-account-migrations.ps1) | 运行中的 MySQL 容器及容器内 root 配置 | 在随机独立库验证指定历史迁移并清理 |

本次文档仅说明脚本，不自动运行配置发布、数据库验证或镜像构建。

## 3. build-images.ps1

顺序执行：

```text
docker compose --env-file .env -f compose.yml config --quiet
→ 检查 Compose 退出码
→ ./mvnw.cmd -B -ntp -Dmaven.compiler.fork=true verify
→ 检查 Maven 退出码
→ docker compose --env-file .env -f compose.yml build
→ 检查 Docker 退出码
```

脚本不跳过测试。Dockerfile 只复制打包好的 JAR，因此 Java 验证和打包必须先完成。Maven、配置检查或镜像构建任意一步失败，后续步骤停止，并恢复调用者原工作目录。

默认使用已提交的 [compose.yml](../compose.yml) include 入口，保留应用构建与 infra 挂载各自的路径基准。支持 `-EnvFile`、`-ComposeFile`；相对参数均以仓库根目录解析，可传绝对路径。先从 [.env.example](../.env.example) 创建自己的私有环境配置，已有文件不要覆盖。

```powershell
./scripts/build-images.ps1 -EnvFile .env -ComposeFile compose.yml
```

脚本会执行本地构建和 Docker 镜像写入，不执行 `up` 或发布。端口、生产 profile 和私有监控配置见 [infra 文档](../infra/README.md)。

## 4. import-nacos.ps1

从进程环境读取 `NACOS_SERVER_ADDR`（默认 `localhost:8848`）、`NACOS_USERNAME`、`NACOS_PASSWORD`（默认均 `nacos`）。依次遍历 `infra/nacos/import/*.yml`，以文件名作为 Data ID，固定 group `AGENT_GROUP`，使用 `/nacos/v1/cs/configs` 发布 YAML。

```powershell
# 仓库根目录；先将实际 Nacos 连接配置放入当前进程环境
$env:NACOS_SERVER_ADDR = '127.0.0.1:8848'
./scripts/import-nacos.ps1
```

本脚本没有自动读取 `.env`，也没有导入前备份 / 差异预览、回滚和全批次事务。发布同一 Data ID 可能覆盖远端现有配置；某个文件失败时，之前成功发布的文件不会自动回滚。

请求采用当前代码中的 v1 发布 API 和用户名 / 密码参数。目标 Nacos 的鉴权、版本和接口可用性需要实际确认；遇到 401 / 403 应检查账户与发布协议，不要为导入而关闭业务身份检查。

## 5. smoke-test.ps1

使用 `Invoke-WebRequest`、10 秒超时，逐个检查：

- Nacos `localhost:8848/nacos/v1/ns/operator/metrics`。
- Gateway 和 6 个领域服务默认端口的 `/actuator/health`。
- Milvus `localhost:9091/healthz`。
- Prometheus `localhost:19090/-/ready`。

```powershell
./scripts/smoke-test.ps1
```

返回非 200 或连接失败立即停止，成功项打印 `OK`。端口写死，没有参数覆盖；改端口或容器不向主机暴露服务时，需要调整检查方式。

脚本不检查健康 JSON 的细节，不覆盖 Zipkin / Sentinel，不执行注册、CSRF、登录、模型调用、文档检索、图片生成或支付。它检查的是一组 HTTP 可达性和返回码，不能当作完整业务验收。

## 6. verify-account-migrations.ps1

参数 `-Container` 默认 `agent-mysql`。脚本通过 `$PSScriptRoot` 定位仓库，生成严格格式的 `codex_billing_verify_<随机GUID>` 数据库名，使用容器内 root 密码执行 SQL。

```powershell
# 运行前确认目标是预期的开发 MySQL 容器
./scripts/verify-account-migrations.ps1 -Container agent-mysql
```

处理流程：

1. 创建独立测试库，不复用业务 schema。
2. 按脚本手工列出的顺序应用 Auth V1–V4、Media V1–V4、Content V1–V3。
3. 在 Auth V1 后插入固定旧账户 fixture。
4. 验证旧用户公开 UUID 保持稳定、历史用户不被补送免费图片，并输出表数量。
5. `finally` 中删除该随机测试库；清理失败给出手工清理提示。

它直接执行 SQL，不通过 Flyway，因此不验证 Flyway 历史、checksum 和 baseline 行为。当前也**没有覆盖 Auth V5–V7 的调用组、免费 Token、RBAC，或 Chat / RAG / Orchestrator 迁移**。脚本名不代表它能验证全仓库所有最新迁移。

该脚本会创建和删除它生成的随机验证库，不应扩大为删除实际业务库。失败时依据脚本输出和确切测试库名处理。

## 7. 日常验证建议

```powershell
# 完整 Java 验证
./mvnw.cmd '-Dmaven.compiler.fork=true' verify

# 单独运行架构门禁及其上游
./mvnw.cmd -pl agent-architecture-tests -am '-Dmaven.compiler.fork=true' test
```

健康检查通过后，再以自己的账号依次验证 CSRF / 登录、聊天、文档上传到索引、持久问答事件、已配置的图片链路。支付需要真实渠道或匹配的沙箱配置，不能靠 HTTP 200 的健康接口证明入账正确。

新增脚本时明确执行目录、参数、环境变量、外部写入、退出码和清理范围；重复使用配置与 owner 规则，避免维护第二套业务实现。

## 8. 支付宝部署预检

在后端根目录运行 `./scripts/verify-payments.ps1 -ExpectedAppId YOUR_ALIPAY_APP_ID`。默认使用后端 `.env`，从正在运行的 Auth 容器 Compose 标签发现原部署入口；也可通过 `-ComposeFiles` 显式指定所有 Compose 文件、`-AuthContainer` 指定容器。`-ConfigurationOnly` 只检查展开配置与宿主机密钥挂载文件，不核对运行容器。

脚本只读 Docker 配置和文件元数据，并在容器内检查密钥文件可读，不调用真实支付、写数据库或输出秘密。成功退出码为 0，失败抛错退出非零；不会创建需清理的临时资产。预检通过不代表商户产品权限或真实到账已验收，后续步骤见 [支付服务实现](../agent-auth-service/src/main/java/com/bbb/exercise/agentdemo/auth/billing/PaymentService.java)。
