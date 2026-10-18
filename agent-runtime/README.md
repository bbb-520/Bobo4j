# agent-runtime 模块帮助文档

## 1. 模块作用与依赖关系

本模块是业务服务共享的运行时库，提供浏览器身份与权限校验、内部 HTTP 客户端、平台模型调用、计费客户端、熔断和 OSS 存储。它不独立启动，没有自己的数据库迁移或业务 HTTP 端口，由各服务的组件扫描 / 导入启用。

依赖方向为 `agent-runtime → agent-common → agent-api`，Runtime 也直接依赖 API。业务服务可以引用 Runtime，但 Runtime 不应依赖某个业务服务的实现类。数据库状态仍由各服务拥有，Runtime 通过 HTTP 获取远端能力。

## 2. 主要代码入口

| 目录 | 作用 |
| --- | --- |
| [config](src/main/java/com/bbb/exercise/agentdemo/runtime/config) | `AppProperties`、`OssProperties` 绑定应用配置 |
| [identity](src/main/java/com/bbb/exercise/agentdemo/runtime/identity) | `ChatIdentityResolver` 从可信上下文或 Auth 会话得到身份 |
| [security](src/main/java/com/bbb/exercise/agentdemo/runtime/security) | WebFlux Security、权限路径映射和身份提供者 |
| [client](src/main/java/com/bbb/exercise/agentdemo/runtime/client) | Auth / Media 内部调用、模型选择、计费、模型执行和熔断 |
| [storage](src/main/java/com/bbb/exercise/agentdemo/runtime/storage) | `OssStorageService` 管理上传策略、签名 URL、对象复制与删除 |
| [web](src/main/java/com/bbb/exercise/agentdemo/runtime/web) | Web 层公共配置 |

## 3. 浏览器身份与权限如何实现

[BrowserSecurityConfiguration](src/main/java/com/bbb/exercise/agentdemo/runtime/security/BrowserSecurityConfiguration.java) 使用无服务端 SecurityContext 会话的 WebFlux Security。认证来源仍是 Auth 管理的 HttpOnly Cookie，而不是客户端发送的用户 ID 或权限头。

1. 对受保护请求提取 `bbb_agent_session`（可配置）。
2. `BrowserIdentityProvider` 校验会话。Auth 服务提供本地实现，其他服务默认通过签名内部请求向 Auth 查询。
3. 将可信用户放入 exchange 属性，并将权限转为 Spring Security authorities。
4. [PermissionPolicy](src/main/java/com/bbb/exercise/agentdemo/runtime/security/PermissionPolicy.java) 按方法和路径选择权限，例如 `chat:use`、`rag:document:manage`、`media:generate`、`agent:execute`。未知路径默认拒绝。
5. 业务 Service 继续检查文档、会话、图片任务等资源是否属于当前用户和租户。

`/internal/**` 在浏览器 Security 链中放行，是为了使用独立的内部签名协议；内部 Controller 仍须验签和检查调用者，不意味着内部接口匿名可用。

`ChatIdentityResolver.resolve` 保留匿名 UUID Cookie 的兼容逻辑；`resolveRequired` 强制登录。当前受保护浏览器路由不能仅凭匿名 Cookie 使用。

### CSRF 的调用要求

浏览器写请求需要 CSRF。先通过 `GET /api/auth/csrf` 获取 `token` 和 `headerName`，保存响应 Cookie，后续 POST / PUT / PATCH / DELETE 同时携带 Cookie 与指定请求头。登录、注册、发送验证码虽然无需登录，仍需要 CSRF。

GET / HEAD / OPTIONS / TRACE、内部接口和两个支付回调免 CSRF。支付回调由支付渠道协议验签。新增业务写接口需要同时检查 `PermissionPolicy` 和 CSRF 行为。

## 4. 内部 HTTP 客户端

[InternalServiceClient](src/main/java/com/bbb/exercise/agentdemo/runtime/client/InternalServiceClient.java) 使用 `WebClient.Builder.clone()` 构造 Auth 和 Media 客户端。每次请求根据调用服务、主体、租户、目标服务和方法路径签发 `X-Internal-Principal`。

它提供会话校验、模型配置、凭据、图片资产、创建图片任务和可发布结果等方法，默认 HTTP 超时 5 秒。非成功响应会转换为受控异常，避免把供应商正文或凭据泄露给上层。资源 ID 还会经过格式校验。

内部地址使用 `AUTH_SERVICE_URL`、`MEDIA_SERVICE_URL` 等配置，当前是明确的 HTTP base URL；Gateway 的 `lb://` 路由则使用注册发现，两种寻址方式需要分别配置。

## 5. 模型调用、兜底与计费

[ModelCallClient](src/main/java/com/bbb/exercise/agentdemo/runtime/client/ModelCallClient.java) 是文本、视觉、Embedding 和 Rerank 的统一执行入口，保留调用组、预算、回执与结算协调。供应商 HTTP/SSE 协议、请求体、流式事件和超时已提取到 [ModelProviderTransport](src/main/java/com/bbb/exercise/agentdemo/runtime/client/ModelProviderTransport.java)，提示词直接使用 API 模块。典型流程为：

```text
获取 Auth 模型配置 → 创建/重入计费调用组 → 估算并预留 Token 上界
→ 记录尝试与派发 → 调用供应商 → 验证用量与响应
→ 保存供应商回执 → 结算 → 返回结果/提交事件
```

同一 `callId`、相同参数已有回执时直接重放；派发结果未知时进入核对流程，避免盲目再次付费调用。`ProviderUsage` 和协议校验要求使用真实供应商用量；缺少 usage 不会被静默当作零 Token。

聊天、视觉、摘要和文档回答在允许且预算足够时，对可重试错误最多尝试一次备用模型；`EVALUATION` 和 `DECISION` 不自动兜底。发生切换时使用 `fallback_started`、`answer_replace` 和 generationVersion 通知消费者。

最终 system 由 API 模块的 `ModelPrompts.assemble` 统一组装，预算估算和供应商请求使用相同结果。调用方补充中的空格与换行原样保留，null 视为空字符串；用户问题和图片仍放在 user 内容中。该收敛保持原有输出上限、图片预算附加量、主备条件和按实际回执结算的规则。

| 环境变量 | 默认秒数 | 控制范围 |
| --- | ---: | --- |
| `MODEL_FIRST_OUTPUT_TIMEOUT_SECONDS` | 15 | 首次输出 |
| `MODEL_IDLE_TIMEOUT_SECONDS` | 20 | 流中空闲 |
| `MODEL_TOTAL_TIMEOUT_SECONDS` | 60 | 整体调用 |
| `MODEL_FALLBACK_TIMEOUT_SECONDS` | 30 | 备用调用上限，仍受整体剩余时间约束 |

[ModelCircuit](src/main/java/com/bbb/exercise/agentdemo/runtime/client/ModelCircuit.java) 按供应商、模型和 base URL 维护失败窗口、打开状态和探测许可；Redis 可用时共享状态，异常时使用有限的本地状态。当前本地规则为 60 秒内 5 次失败后打开 30 秒。

## 6. OSS 如何使用

[OssStorageService](src/main/java/com/bbb/exercise/agentdemo/runtime/storage/OssStorageService.java) 根据 `app.oss` 配置执行 OSS 上传策略、元数据查询、限时下载、远程图片落盘到对象存储、对象复制与删除。业务表只保存对象 key，短期签名 URL 在读取时生成。

主要配置为 `OSS_ENABLED`、`ALIYUN_OSS_BUCKET`、`ALIYUN_OSS_ENDPOINT`、`ALIYUN_OSS_REGION`、`ALIYUN_OSS_ACCESS_KEY_ID`、`ALIYUN_OSS_ACCESS_KEY_SECRET`。默认上传与签名 URL TTL 为 600 秒，最大对象大小为 20 MiB。远程 URI 读取含安全检查，不能扩展成任意 URL 下载代理。

## 7. 使用、测试与排错

```powershell
# 仓库根目录执行；这是库模块，不使用 java -jar 启动
mvn -pl agent-runtime -am '-Dmaven.compiler.fork=true' test
```

[测试目录](src/test/java/com/bbb/exercise/agentdemo/runtime) 覆盖模型流式协议、用量和兜底、权限路径、浏览器认证 / CSRF、OSS 配置。引用库之后还需要确认服务启动类的扫描范围包含需要的 Runtime 组件。

- **401**：检查 Cookie 名称、有效期、Auth 可达性和内部签名配置。
- **403**：检查 CSRF Cookie / header 和用户权限；授权通过后仍可能被资源所有权检查拒绝。
- **模型配置缺失**：检查 Auth 的平台模型环境变量，而不是向浏览器暴露供应商 Key。
- **UNKNOWN / 待核对**：查看 Auth 的调用组与消费记录，保留原 `callId` 和回执。
- **OSS URL 失效**：重新通过业务读取接口获取签名 URL，不把 URL 当永久资产 ID。

完整运行步骤见 [infra](../infra/README.md)，计费状态详见 [Auth](../agent-auth-service/README.md)。
