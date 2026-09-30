# BoboWorld4J 绞杀式迁移设计

**状态：** 实施中（第一批安全与任务可靠性修复已落地，领域切流仍未完成）  
**日期：** 2026-09-30  
**范围：** BoboWorld4J 从复制式伪微服务迁移到具备真实领域边界的服务架构

## 1. 背景与决策

当前工程已形成多个可独立启动的 Maven 模块，但 Auth、Chat、Media、Content 和 Legacy 仍复制并扫描 `com.bbb.exercise.agentdemo1_0` 下的大量单体代码。服务之间共享数据库表、重复 Flyway 脚本、重复配置和重复业务类，网关身份头可由客户端伪造，关键运行路径缺少真实数据库与 Redis 集成测试。

本项目采用绞杀式迁移，不进行一次性重写，也不继续在复制结构上扩展。迁移期间允许新旧实现短期共存，但任何业务能力只能指定一个主实现和一个数据写入者；流量按领域逐步切换，Legacy 最终退出主链路。

过渡阶段继续使用同一个 MySQL 8.4 实例，但按服务建立严格的数据所有权。物理拆库不属于本轮迁移的前置条件。

## 2. 目标

1. Gateway 成为唯一公网业务入口，客户端不能声明可信身份。
2. 每个领域只有一个生产实现、一个 Java 包边界和一个数据写入者。
3. Auth、Chat、Media、Content、Orchestrator 通过明确 API 交互，不直接读写其他服务拥有的表。
4. 修复当前 P0 正确性与安全问题，包括明文 API Key、图片任务插入失败、身份伪造、Redis 记忆隔离、SSE 中断丢消息和 OSS SSRF。
5. 用 ArchUnit、契约测试和 Testcontainers 把边界与关键行为固化为自动化门禁。
6. 前端流量迁移到 Gateway 后，再隔离并退役 Legacy。

## 3. 非目标

- 本轮不要求立即拆成多个物理数据库实例。
- 本轮不引入 Kubernetes、Kafka 或新的分布式事务框架。
- 本轮不同时重写全部业务功能。
- 在基础边界稳定前，不扩展新的 Agent 工作流种类。
- 不保留仅为单元测试服务的生产内存回退路径。

## 4. 设计原则

### 4.1 单一所有者

一张表、一个业务能力、一个生产实现只能有一个 owner。其他服务通过 API 或领域事件访问该能力。

### 4.2 新旧分流而非双写

迁移期间按 endpoint 或业务能力切流。默认禁止新旧实现同时写同一业务事实表，避免双写一致性问题。

### 4.3 先安全与正确，再重构结构

身份、密钥、任务状态和数据隔离问题必须先修复，然后才迁移包与删除重复代码。

### 4.4 契约先行

公共 HTTP API、内部 API、错误响应和领域标识先形成契约，再移动实现。Gateway 路由必须由契约测试覆盖。

### 4.5 可回滚

每个迁移批次必须有独立验收和流量回退方案。数据库迁移采用向前兼容方式，不以删除 Flyway 历史作为回滚手段。

## 5. 目标拓扑

```text
Client
  │ HttpOnly session cookie
  ▼
agent-gateway
  ├── authentication/introspection
  ├── request-id and audit context
  ├── rate limit / timeout / circuit breaker
  ├── route contract
  └── signed internal principal
        │
        ├── agent-auth-service
        ├── agent-chat-service
        ├── agent-media-service
        ├── agent-content-service
        └── agent-orchestrator-service

Shared infrastructure during migration:
  MySQL 8.4 (logical ownership by service)
  Redis Stack (Chat-owned keys)
  Aliyun OSS (Media-owned binary lifecycle)
  Nacos / Sentinel
```

`agent-chat-legacy` 不在主拓扑中。它使用独立服务名，不参与 Gateway 主路由和生产 Nacos 负载均衡，只在受控回滚窗口中启动。

## 6. 模块职责

### 6.1 agent-common

只允许保存无业务归属的公共能力：

- 统一错误结构 `ApiErrorResponse`
- 请求 ID 与审计上下文
- 内部身份载体与签名验证器
- 时间、分页等稳定值对象
- 公共 Web 异常映射基础设施

禁止放入 AuthService、业务实体、JdbcTemplate Repository、OSS 客户端或模型 Provider 实现。

### 6.2 agent-api

保存稳定的 HTTP 契约 DTO 和内部客户端接口。接口必须有实际调用方；无调用方的契约删除。契约不包含数据库实体和密钥明文。

### 6.3 模型能力归属

本次迁移确认模型配置和 Provider 注册表由 Auth/Common 共同承担：Auth 拥有用户模型配置，Common 提供无状态 Provider 描述和共享 DashScope 适配器。原先没有生产调用方的 `agent-model-core` 已删除，避免保留空依赖和第二套路由抽象。

### 6.4 领域服务

建议包结构：

```text
com.bbb.exercise.agent.auth
com.bbb.exercise.agent.chat
com.bbb.exercise.agent.media
com.bbb.exercise.agent.content
com.bbb.exercise.agent.orchestrator
```

每个服务内部按 `web / application / domain / infrastructure` 分层。所有新启动类只能扫描自身领域包和明确的公共配置包，禁止继续扫描 `com.bbb.exercise.agentdemo1_0`。

## 7. 身份与安全

### 7.1 外部身份流

1. 客户端只发送服务端签发的 HttpOnly session cookie。
2. Gateway 首先删除请求中的 `X-User-Id`、`X-Tenant-Id`、`X-Principal-*`。
3. Gateway 使用 cookie 调用 Auth 内部 introspection API。
4. Auth 返回 `principalId`、`tenantId`、`roles` 和 `expiresAt`。
5. Gateway 使用共享轮换密钥签名内部 Principal，并注入下游请求。
6. 下游服务验证签名、过期时间和请求 ID；验证失败返回 401。

内部身份字段：

```text
X-Principal-Id
X-Tenant-Id
X-Principal-Roles
X-Principal-Expires
X-Principal-Signature
```

签名内容必须覆盖 HTTP method、规范化 path、principal、tenant、expires 和 requestId，防止跨请求重放。

### 7.2 内部 API

`/internal/**` 不允许直接公网访问。第一阶段使用内部签名 token，部署条件允许后可升级为 mTLS。InternalAuthController 和 InternalMediaController 必须查询真实数据，不允许返回固定成功结果。

### 7.3 Cookie 与 CSRF

- 由受信任的 Forwarded Header 策略决定 HTTPS，不直接相信任意客户端 `X-Forwarded-Proto`。
- 生产 Cookie 固定 `Secure=true`、`HttpOnly=true`、`SameSite=Lax`；确需跨站时使用 `SameSite=None` 并配置严格 Origin 白名单。
- 登录、注销、设置修改和发布类接口启用 CSRF token 或同源双提交保护。
- 登录增加基于账号和来源 IP 的速率限制。

### 7.4 API Key 加密

删除明文透传的 `ApiKeyCrypto`。采用带版本和 keyId 的 AES-256-GCM envelope：

```text
version | keyId | nonce | ciphertext | authenticationTag
```

主密钥只从 Secret 注入。读取旧明文时进行一次性迁移，写入永远产生密文。API 只返回 `configured=true` 和非敏感 provider 信息，不再回显 Key 的首尾字符。

## 8. 数据所有权

| 表 | Owner | 其他服务访问方式 |
| --- | --- | --- |
| `app_user` | Auth | Auth API |
| `auth_session` | Auth | Auth introspection API |
| `user_api_key` | Auth | Auth model credential API |
| `user_model_profile` | Auth | Auth model selection API |
| `chat_conversation` | Chat | Chat API |
| `chat_message` | Chat | Chat API |
| `vision_memory` | Chat | Chat API |
| `image_asset` | Media | Media API |
| `image_job` | Media | Media API |
| `bobo_world_item` | Content | Content API |
| `agent_run` | Orchestrator | Orchestrator API |

迁移约束：

- 删除 Chat、Media、Content 中复制的 Auth 表迁移。
- 删除或封存过期的 `infra/mysql/migrations` 建表脚本。
- 不新增跨服务外键。
- 过渡期继续同库时，通过专用数据库账号和表权限限制越界访问。

## 9. Gateway 与 API 契约

当前已对齐的主路径继续作为规范路径：

```text
/api/auth/**                       -> auth
/api/settings/keys/**              -> auth
/api/settings/models/**            -> auth
/api/chat/**                       -> chat
/api/settings/visual-memory/**     -> chat
/api/zine/**                       -> chat，迁移后转 content
/api/image-assets/**               -> media
/api/image-jobs/**                 -> media
/api/bobo/**                       -> content
/api/agent-runs/**                 -> orchestrator
```

不存在真实 Controller 的 `/api/conversations/**`、`/api/photos/**`、`/api/images/**` 和复数 `/api/zines/**` 不进入正式契约。若产品需要这些能力，必须先在 owner 服务实现，再增加路由。

公共 API 使用 OpenAPI 文档作为契约来源；Gateway 路由测试必须验证每条路由存在目标 Controller 或明确的下游契约。

Actuator 对外仅暴露 health；metrics、prometheus 和 info 只能在内部管理端口访问。

## 10. Chat 迁移设计

### 10.1 会话记忆隔离

所有模型调用必须显式设置：

```text
conversationKey = chat:{tenantId}:{principalId}:{conversationId}
```

禁止使用 Spring AI 默认 conversation key。Redis 集成测试必须证明两个用户和两个会话之间不会互相读取记忆。

### 10.2 SSE 持久化

处理流程：

1. 接收请求后先写入 user message。
2. 创建 `completed=false` 的 assistant message。
3. 模型流产生内容时在内存中聚合，并按固定阈值更新 partial content。
4. 正常结束时写入完整内容并设置 `completed=true`。
5. cancel、timeout 或 provider error 时保存已生成内容，保持 `completed=false` 并记录结束原因。

SSE 模型调用关闭透明自动重试，避免重复输出和重复计费。

### 10.3 视觉记忆

`VisionMemoryService.augment()` 进入正式 prompt 组装路径；召回数量、使用次数和时间权重必须可测试。时间评分统一使用 `Instant` 或明确的 epoch seconds，不混用数量级。

## 11. Media 迁移设计

### 11.1 单一租约入口

删除双重 claim。`ImageJobService.claimNext(workerId, leaseDuration)` 是唯一入口，在一个事务内完成：

1. `SELECT ... FOR UPDATE SKIP LOCKED`
2. `QUEUED -> PROCESSING`
3. `attempt_count = attempt_count + 1`
4. 写入 `lease_owner`、`lease_until`、`started_at` 和 `updated_at`

完成和失败更新必须同时检查 `id`、`status=PROCESSING` 和 `lease_owner`。

### 11.2 Schema 正确性

`image_job` 创建语句必须写入非空 `updated_at`。Testcontainers MySQL 在 STRICT_TRANS_TABLES 下执行真实 INSERT，作为回归测试。

### 11.3 OSS 安全

远程图片下载使用单例 HttpClient，并具备：

- HTTP/HTTPS 协议白名单
- provider 域名白名单
- DNS 解析后的私网、回环和链路本地地址拒绝
- 连接与读取超时
- 最大 Content-Length 和流式字节上限
- MIME 与文件签名校验
- 临时文件 finally 清理

上传 policy 使用 JSON 序列化器生成，不手工拼接 JSON。

### 11.4 清理任务

Media 定期处理过期 PENDING asset、终态 job 和无引用 OSS 对象。Content 通过 Media API 请求删除 Bobo 条目对应对象，不直接操作 Media 的表。

## 12. Content 迁移设计

Content 最终拥有 Bobo、Photo 和 Zine。Zine 在迁移完成前仍由 Chat 暂时提供，迁移时先形成 API 契约，再移动实现和路由。

`BoboWorldService` 查询使用明确列名，避免 `SELECT b.*`；用户显示名通过发布快照或 Auth 批量 API 获取，不使用表达式连接扫描 Auth 表。

缩略图策略先保持 OSS 实时处理，增加合理缓存头；只有性能指标证明需要时再增加持久化缩略图任务。

## 13. Orchestrator 迁移设计

第一阶段将 Orchestrator 定义为持久化工作流状态机，不宣称完整 Agent 执行平台。它提供：

- 创建 run
- owner 约束的查询与推进
- 乐观锁 version
- 超时与恢复扫描
- 审计事件
- 明确的终态规则

生产环境删除 `ConcurrentHashMap` 回退。状态更新必须包含：

```sql
WHERE id = ? AND user_id = ? AND version = ?
```

第二阶段再引入 `WorkflowDefinition`、`WorkflowExecutor`、`StepExecutor` 和 outbox 事件，不在本轮边界收敛中提前实现。

## 14. Legacy 策略

1. 服务名改为 `agent-chat-legacy`。
2. 默认不注册生产 Nacos。
3. 数据库、Redis 和 Cookie 配置与迁移环境对齐。
4. 前端从 Legacy 静态资源迁移到 Gateway 主入口。
5. 每个领域流量切换后，在 Legacy 中关闭对应写接口。
6. 所有领域切换并稳定一个发布周期后删除 Legacy。

Legacy 不能与新 Chat 使用相同 discovery service name，也不能在 Gateway 主路由中作为随机实例参与负载均衡。

## 15. 测试与质量门禁

### 15.1 单元测试

覆盖状态机、参数校验、Provider 路由、API Key 加解密、OSS URL 校验、Worker 重试和记忆键。

### 15.2 ArchUnit

强制以下规则：

- 不同服务不得声明重复的业务类全限定名。
- 领域服务不得依赖其他领域服务的 infrastructure 包。
- Controller 不直接依赖 JdbcTemplate。
- `com.bbb.exercise.agentdemo1_0` 不得被新服务启动类扫描。
- 只有表 owner 模块能包含对应 Flyway migration。

### 15.3 Testcontainers

至少使用 MySQL 8.4 和 Redis Stack 验证：

- 所有 Flyway migration 可从空库执行。
- `image_job` 可在严格模式创建、领取、失败重试和成功终结。
- 不同用户与会话的 Redis 记忆隔离。
- API Key 入库为密文且可轮换解密。
- owner 条件阻止跨用户读取和更新。

### 15.4 契约与端到端

- Gateway 拒绝客户端伪造身份 Header。
- 未登录、过期会话和合法会话响应正确。
- 每条 Gateway 路由能够命中目标 Controller。
- SSE 正常结束、取消、超时和 provider error 均保留消息状态。
- OSS 下载拒绝 localhost、私网 IP、超大文件和非图片内容。

每一个 P0 缺陷必须对应至少一条行为回归测试。

## 16. 迁移阶段与退出条件

### Phase 0：基线冻结

交付：缺陷台账、API 清单、表 owner 清单、当前测试基线和可重复构建命令。  
退出条件：完整 Maven 测试和打包可重复执行；新增业务功能暂停进入旧包。

### Phase 1：P0 安全与正确性

交付：可信 Gateway 身份流、API Key 加密、Media 单一租约、`updated_at` 修复、Internal API 保护、OSS 下载防护。  
退出条件：对应安全与 Testcontainers 回归全部通过；客户端身份 Header 无法伪造。

### Phase 2：边界与数据所有权

交付：Auth 唯一化、重复 migration 删除、数据库账号权限隔离、ArchUnit 边界规则。  
退出条件：Chat、Media、Content 不再包含 AuthService 副本，也不直接查询 Auth 表。

### Phase 3：Chat 与 Media 可靠性

交付：会话记忆隔离、SSE 部分持久化、模型客户端复用、清理任务和 Worker profile。  
退出条件：Redis/Testcontainers 和 SSE 中断测试通过；图片任务真实端到端通过。

### Phase 4：Content、前端与 Gateway 切流

交付：Zine/Photo 归属收敛、前端通过 Gateway、OpenAPI 与路由契约一致。  
退出条件：主要用户流程不再直接访问 Legacy 或内部服务端口。

### Phase 5：Orchestrator 收敛与 Legacy 退场

交付：持久化状态机、恢复扫描、Legacy 隔离和退役清单。  
退出条件：Legacy 不再承载生产写流量，并经过一个稳定发布周期。

## 17. 发布与回滚

每个 Phase 独立发布：

1. 先发布向后兼容的 schema。
2. 再发布新的 owner 服务。
3. 运行契约、数据一致性和关键用户流程检查。
4. 最后切换 Gateway 路由或功能开关。

回滚只切换路由和应用版本；数据库采用向前兼容迁移。Media Worker 回滚前必须暂停任务领取，确认 PROCESSING lease 已到期或完成交接。

## 18. 风险与控制

| 风险 | 控制措施 |
| --- | --- |
| 新旧实现同时写表 | 路由级切流，禁止双写 |
| 身份切换导致登录失效 | Cookie 兼容期和 introspection 契约测试 |
| 删除复制代码时遗漏调用 | `rg` 引用审计、ArchUnit 和全量编译 |
| Flyway 所有权调整冲突 | 空库演练、现有库 repair 禁止自动执行、发布前备份 |
| SSE 行为改变 | 正常/取消/超时三类端到端测试 |
| Worker 切换产生重复任务 | lease owner、终态幂等和切换前暂停领取 |

## 19. 完成标准

迁移完成必须同时满足：

1. 新服务不再扫描或复制 `com.bbb.exercise.agentdemo1_0`。
2. 每张表只有一个 Flyway owner 和一个写服务。
3. Gateway 不信任客户端身份 Header。
4. API Key 不以明文落库或回显。
5. Chat 记忆按 tenant/user/conversation 隔离。
6. 图片任务在真实 MySQL 上完成创建、领取、重试和终态转换。
7. OSS 远程下载具备 SSRF、超时和大小限制。
8. 前端所有生产 API 流量经过 Gateway。
9. Legacy 不注册为新 Chat 的同名实例，也不再接收生产写流量。
10. Maven 全量测试、Testcontainers、Gateway 契约和核心冒烟全部通过。

