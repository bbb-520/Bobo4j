# agent-auth-service 模块帮助文档

## 1. 模块作用与边界

Auth 是账户、安全授权、平台模型配置、钱包计费和支付的权威服务，默认端口 `18081`。启动入口是 [AuthServiceApplication](src/main/java/com/bbb/exercise/agentdemo/authservice/AuthServiceApplication.java)。

本模块同时包含 `auth` 业务包和 `authservice` 启动 / 内部接口包。其他服务通过签名 HTTP 查询身份、模型和计费状态，不能直接读取 Auth 的表或引用其 Service 实现。

## 2. 核心代码导航

| 位置 | 主要职责 |
| --- | --- |
| [AuthController](src/main/java/com/bbb/exercise/agentdemo/auth/AuthController.java) | 注册、登录、注销、当前用户、CSRF 和邮箱入口 |
| [AuthService](src/main/java/com/bbb/exercise/agentdemo/auth/AuthService.java) / [AccountService](src/main/java/com/bbb/exercise/agentdemo/auth/AccountService.java) | 会话、账户注册、邮箱绑定与注册钱包 |
| [email](src/main/java/com/bbb/exercise/agentdemo/auth/email) | QQ 邮箱验证码、限流和 SMTP 发送 |
| [rbac](src/main/java/com/bbb/exercise/agentdemo/auth/rbac) | 角色权限、管理员初始化、禁用用户与审计 |
| [PlatformModels](src/main/java/com/bbb/exercise/agentdemo/auth/PlatformModels.java) / [model](src/main/java/com/bbb/exercise/agentdemo/auth/model) | 按能力返回平台模型配置 |
| [billing](src/main/java/com/bbb/exercise/agentdemo/auth/billing) | 余额、Token 配额、调用组、结算、对账、退款和充值订单 |
| [payment](src/main/java/com/bbb/exercise/agentdemo/auth/payment) | 支付宝 SDK 接入、验签、退款与资金对账 |
| [InternalAuthController](src/main/java/com/bbb/exercise/agentdemo/authservice/InternalAuthController.java) / [InternalBillingController](src/main/java/com/bbb/exercise/agentdemo/authservice/InternalBillingController.java) | 受签名和调用者边界保护的内部协议 |

## 3. 账户和会话如何实现

### 注册

1. 客户端先取 CSRF，再以 `purpose=REGISTER` 申请邮箱验证码。
2. `AccountService.register` 校验并消费验证码，要求 `@qq.com` 邮箱。
3. 事务内创建用户、绑定邮箱、赋予默认 `USER` 角色并创建钱包。
4. 当前新注册赠送 `1,000,000` 免费 Token，免费图片为 `0`。
5. 注册响应 `authenticated=false`，注册本身不自动登录。

注册 JSON 只接受 `username`、`password`、`qqEmail`、`code`，拒绝额外字段。用户名为 3–64 位允许字符，密码为 8–128 位。密码使用 BCrypt（cost 12）存储，见 [PasswordHasher](src/main/java/com/bbb/exercise/agentdemo/auth/PasswordHasher.java)。

### 登录与会话

密码登录或邮箱验证码登录成功后签发随机会话 token，数据库仅保存 SHA-256 哈希，默认有效期 30 天。浏览器通过 HttpOnly Cookie 持有原 token；HTTPS / Secure 配置影响 SameSite 和 Secure 属性。

Auth 内部业务身份使用 `user:<数据库ID>`，对外公开 ID 由稳定 UUID 规则生成并保存。业务跨服务使用公开用户 ID，不能把这两种 ID 混用。用户禁用后删除其已有会话；登录签发和禁用共享用户行锁，降低并发绕过风险。

### 邮箱验证码

[QqEmailService](src/main/java/com/bbb/exercise/agentdemo/auth/email/QqEmailService.java) 生成 6 位验证码，以邮箱 + 用途 + 验证码的 HMAC 保存；用途仅 `REGISTER`、`LOGIN`、`BIND`。有效期 5 分钟，最多 5 次校验尝试，成功后不可重复消费。

同邮箱 60 秒最多发送一次，来源 IP 每小时最多 20 次。限流与消费在数据库事务 / 行锁内执行，SMTP 在锁外发送。邮件只允许 QQ 邮箱，绑定为一次性操作；更换绑定邮箱没有通用恢复接口。

## 4. RBAC 授权如何实现

[RbacService](src/main/java/com/bbb/exercise/agentdemo/auth/rbac/RbacService.java) 从用户角色和角色权限表计算实时快照。`USER` 获得基础业务权限；管理接口需要 `user:read`、`user:disable`、`billing:reconcile`、`billing:refund`、`audit:read` 等权限。

角色授权和权限配置仅由内置超级管理员执行，内置角色不能通过普通角色修改接口改写或删除。授予 `SUPER_ADMIN` 还需当前有效会话和再次验证密码；不能移除或禁用最后一位有效超级管理员。管理变更需要 1–500 字操作原因并记录审计。

首次管理员仅通过显式启动参数初始化：

```powershell
# 指定的用户须已正常注册、邮箱完全匹配且处于 ACTIVE 状态
java -jar agent-auth-service/target/agent-auth-service-0.0.1-SNAPSHOT.jar --rbac-bootstrap-username=<已有用户名> --rbac-bootstrap-email=<对应QQ邮箱> --rbac-bootstrap-exit
```

这是一条会授予权限的运维命令，示例占位符须替换。已有其他有效超级管理员时，应通过管理接口授权。`BILLING_ADMIN_USER_IDS` 用于迁移旧计费运营白名单，不代表超级管理员初始化入口。

浏览器权限和 CSRF 由 [Runtime 安全组件](../agent-runtime/README.md) 执行，Auth 提供本地身份查询，其他服务经内部 HTTP 查询授权快照。

## 5. 平台模型配置

`PlatformModels` 按聊天、视觉、图片生成、Embedding、Rerank、Evaluation 和 Fallback 能力选择千问配置；具体以 [application.yml](src/main/resources/application.yml) 和实际环境配置为准。

当前采用**平台统一配置凭据**。`UserApiKeyService` 和 `ModelProfileService` 保留查询 / 兼容入口，个人上传、删除 Key 或保存模型的写入口继续返回 `410`。历史 `user_api_key`、`user_model_profile` 表保留，当前未开放个人自带模型写入。

平台托管文本与评估默认 `qwen-plus`，视觉默认 `qwen-vl-max`；备用分别为 `qwen-turbo` 和 `qwen-vl-plus`。全部使用千问凭据，旧平台 GPT Provider、通用密钥及地址配置已移除；供应商兼容目录继续保留。详见根 README 的模型配置表。闲置的个人凭据加密组件及个人模型表映射已删除，既有数据库迁移和历史数据保留。

## 6. 钱包、调用组和对账

金额使用整数微元：`1 元 = 1,000,000 微元`；Token 是独立计量。充值接口输入整数分：`100 分 = 1 元`。

简单消费由 `BillingService` 实现预留、派发、结算、取消 / 未知标记；文本模型由 `ModelCallGroupService` 进一步组织主模型与备用模型尝试：

调用组的归属读取、钱包/预算锁获取和状态汇总已提取至 [ModelCallGroupRepository](src/main/java/com/bbb/exercise/agentdemo/auth/billing/ModelCallGroupRepository.java)。事务及状态转换仍由 Service 管理，钱包与预算锁的既有顺序保持一致。

```text
逻辑 callId + 参数哈希 → 调用准入 / 预留 → PRIMARY 尝试
→ 可重试时 FALLBACK 尝试 → 持久化供应商回执
→ 选择有效结果并结算 → 重入时重放原回执
```

generation 和参数哈希阻止旧尝试或改参请求覆盖现有调用。免费 Token 由 `TokenAllowance` 参与计算；已派发但结果未知的尝试保留预留 / 待对账状态，不能靠重启清零或重新付费重试。同组平台故障预算、时间窗口额度和未解决尝试上限用于限制异常成本。

管理员可查询消费和调用组并进行对账 / 站内退款。具体允许的操作及状态以 Billing Controller 和 Service 的校验为准，站内退款并不等于向支付宝发起原路退款。

## 7. 充值支付流程

1. 已登录用户发送 `POST /api/payments/orders`，带唯一 `Idempotency-Key`，例如 `{"channel":"ALIPAY","amountCents":100}`。
2. `PaymentService` 创建幂等订单，并用预下单租约减少并发重复下单。
3. `PaymentGateway` 获取二维码地址；客户端轮询订单状态。
4. 支付渠道回调到 `/api/payments/callback/alipay`。
5. 后端验证签名、商户、金额、币种和交易状态，事务内一次入账。支付宝订单查询及后台任务可查单补偿丢失的回调；超时订单先向渠道关单，未知结果保留待核对。
6. 持有 `billing:refund` 的管理员可通过 `/api/payments/admin/orders/{id}/refund` 发起一次全额支付宝原路退款。先扣回未消费、未预留的钱包余额，再以持久化退款 ID 调用渠道；未知结果持续恢复，确认成功后标记 `REFUNDED`。

配置预检、0.01 元验收及退款说明见 [支付服务实现](../agent-auth-service/src/main/java/com/bbb/exercise/agentdemo/auth/billing/PaymentService.java)。

仅使用支付宝 SDK 预创建；微信支付链路已移除。支付默认关闭，需商户配置和可到达的通知地址。回调免浏览器登录 / CSRF，但必须通过支付协议校验。

## 8. 主要接口

| 路径 | 用途 |
| --- | --- |
| `GET /api/auth/csrf` | 获取 CSRF token 与 headerName |
| `POST /api/auth/register`、`/login`、`/logout` | 注册、密码登录和注销 |
| `GET /api/auth/me` | 账户、邮箱、角色和权限 |
| `POST /api/auth/email/code`、`/login`、`/bind` | 发送验证码、邮箱登录、绑定 |
| `/api/admin/users`、`/roles`、`/permissions`、`/audits` | 用户管理、角色权限和审计 |
| `/api/settings/keys/**`、`/api/settings/models/**` | 平台配置查询及已退役的个人设置写接口 |
| `GET /api/billing/wallet`、`/api/billing/usage` | 钱包和消费记录 |
| `/api/billing/admin/**` | 管理员对账、退款和调用组管理 |
| `POST /api/payments/orders`、`GET /api/payments/orders/{id}` | 充值订单 |
| `/internal/auth/**`、`/internal/billing/**` | 服务间身份 / 模型 / 计费协议，不经 Gateway 公网路由 |

## 9. 数据和配置

迁移目录：[db/migration](src/main/resources/db/migration)，历史表 `flyway_schema_history_auth`，当前覆盖 V1–V8。

| 表组 | 主要表 |
| --- | --- |
| 账户 / 会话 | `app_user`、`auth_session`、历史凭据与模型配置表 |
| 邮件 | `email_code`、`email_send_limit` |
| 权限 | `app_role`、`app_permission`、`app_user_role`、`app_role_permission`、`authz_audit_log` |
| 钱包 / 充值 | `billing_wallet`、`model_usage`、`payment_order`、`payment_refund`、`usage_reconciliation` |
| 调用组 | `model_call_group`、`model_call_attempt`、尝试历史、故障预算和对账表 |

关键环境配置包括：`MYSQL_*`、`NACOS_*`、内部签名 / Cookie 配置；`SMTP_*`、`AUTH_OTP_SECRET`（至少 32 字符）；`QWEN_API_KEY`、各 `PLATFORM_*` 模型参数；`PLATFORM_RERANK_BASE_URL` 须填实际区域 / 工作空间地址；`BILLING_*`；`PAYMENT_NOTIFY_BASE_URL`、`ALIPAY_*`。完整默认值见模块 YAML，部署覆盖见 infra（本地路径 `../infra/README.md`，不随核心源码发布）。

## 10. 构建、测试和排错

```powershell
# 仓库根目录；先准备 MySQL、Nacos、内部签名和所需业务配置
mvn -pl agent-auth-service -am '-Dmaven.compiler.fork=true' package
java -jar agent-auth-service/target/agent-auth-service-0.0.1-SNAPSHOT.jar
```

另开终端运行 `mvn -pl agent-auth-service -am '-Dmaven.compiler.fork=true' test`。测试覆盖账户持久化、禁用并发、注册字段、RBAC、内部边界、验证码、千问模型路由、免费 Token、计费调用组和支付校验，见 测试目录（本地路径 `src/test/java/com/bbb/exercise/agentdemo`，不随核心源码发布）。

- 401：会话过期、账户禁用、验证码错误或内部签名失败。
- 403：CSRF、缺少权限、需要超级管理员或再次密码验证。
- 409：重复账号 / 邮箱、并发状态冲突、幂等参数不同、调用结果仍待核对。
- 410：调用了已退役的个人模型 / 凭据写入口。
- 402 / 429：额度、余额、模型准入或频率限制；结合响应和消费状态判断。
- SMTP 发送失败：检查 SMTP SSL / STARTTLS、账号授权码、发件人和 OTP secret。
- 支付未入账：查看订单、渠道通知验签和金额核对结果，勿手工伪造成功状态。

支付稳定性、退款 REVIEW 人工终态、二维码刷新、V9 迁移和管理员对账接口见[根 README](../README.md#支付链路稳定性更新2026-10-12)。
