# 绞杀式迁移执行计划

## 当前基线

- Gateway 已只暴露领域公网路由；客户端提交的 `X-User-Id`、`X-Tenant-Id` 和内部身份头会被删除。
- Orchestrator 的写入、读取和状态推进改为通过 Auth `/api/auth/me` 校验会话，身份服务不可用时快速失败；JDBC 调用移出 WebFlux 事件线程。
- Media 的 `image_job` 创建补齐非空 `updated_at`，过期 processing lease 可被重新领取，完成/失败更新带时间戳并保留 owner fencing。
- 旧的 Media ownership 假接口 fail-closed 停用。
- 四个服务的 API Key 存储改为版本化 AES-256-GCM（`v1:` envelope），缺失密钥或旧明文记录均 fail-closed。
- OSS 远程结果下载增加协议、用户信息、DNS 内网地址、连接/响应超时和文件大小校验。
- Auth 内部用户查询不再无条件返回 authenticated=true，改为按稳定 public user id 查询并校验 tenant。
- Media 生产 Worker 移除第二套 `WorkerLeaseService` 领取流程，统一使用 `ImageJobService` 的原子 claim/lease；旧 helper 仅保留测试兼容性。
- Chat SSE 在 complete/error/cancel 三种生命周期都持久化 assistant 内容，取消时标记为 incomplete。
- OSS 远程结果改为流式读取并在读取过程中执行最大字节数限制。
- 公共模块增加短期 HMAC `SignedPrincipal`，Auth 内部用户接口已强制验证该 principal。
- Signed Principal 支持 `keyId` 多密钥并行验证，Auth 可通过 `INTERNAL_PRINCIPAL_SECRETS` 无停机轮换。
- Legacy 默认进入只读模式，业务写请求返回 410，图片 Worker 默认关闭；登录、注册、登出仍保留。

## 阶段 0：门禁与观测（进行中）

1. 保留 Maven 全量回归作为每批迁移门禁；增加 Gateway、Auth relay、任务状态转换的真实边界测试。
2. 增加每个服务的 readiness、request-id、身份校验失败指标；禁止 actuator 在公网暴露敏感端点。
3. 为数据库迁移脚本做版本清单，禁止复制服务继续执行其他领域的 Flyway 表。

## 阶段 1：身份与密钥（下一批）

1. Auth 独占 `app_user/auth_session/user_api_key/user_model_profile`；其他服务删除这些表的 mapper 和 Flyway。
2. 抽取一个共享的 AES-256-GCM 凭据组件，密钥仅从环境/Secret 注入；当前四个模块已先完成同契约实现，后续删除复制类并保留一次性密文迁移命令，不保留静默明文回退。
3. 网关只转发 session cookie；服务间调用使用短期签名 principal，校验 issuer、audience、过期时间和 tenant。

## 阶段 2：领域切流

1. Chat 先接管 `/api/chat/**`，明确 `tenant/user/conversationId` 作为记忆键；SSE 完成、错误、取消都持久化 assistant 消息状态。
2. Media 接管 `/api/image-assets/**`、`/api/image-jobs/**`；将 claim、lease recovery、输出提交收敛到单一 worker 实现，并给远程图片下载加 SSRF、DNS、大小和超时限制。
3. Content 接管 `/api/bobo/**`，只通过 Media 发布接口引用成功任务，不读取 Media 表。
4. 前端切换到 Gateway 公网地址，按 endpoint 灰度 1%→10%→50%→100%，每阶段观察 4xx/5xx、SSE 中断率和任务重复率。

## 阶段 3：Orchestrator 与退役

1. Orchestrator 接管 `/api/agent-runs/**`，把工作流执行器从“状态记录”升级为可恢复的 outbox/step runner。
2. Legacy 进入只读兼容期；停止调度、写入和公网路由，保留可回滚镜像与数据库备份窗口。
3. 连续一个发布周期无流量后移除 Legacy 模块、旧配置和复制包；再物理拆库。

## 每批交付标准

- 先写能复现问题的回归测试并看到失败，再改实现。
- 运行受影响模块测试、全量 Maven 测试和 `-DskipTests package`。
- 检查 `git diff`，不覆盖用户已有迁移改动；记录尚未验证的 MySQL/Redis/OSS 集成项。
