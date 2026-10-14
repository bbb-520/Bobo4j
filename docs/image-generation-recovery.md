# 图片连接失败与账户恢复

2026-10-14 修复：DashScope 图片请求复用了已被上游关闭的连接，发送后收到 connection reset。图片任务已经 FAILED，但 UNKNOWN 消费仍使整账户忙碌，旧策略需等待约十分钟回收。

## 当前行为

- 每次 DashScope 生图使用独立连接，连接超时 10 秒，保留配置的响应超时；关闭 HTTP 客户端透明重试，避免不明结果被重复收费。
- Worker 报告无明确回执的图片调用失败时，Auth 在钱包事务内写入 ABANDONED 审计，记录原状态与平台预估费用，释放对应占用。未知用量保持 NULL，不能当成确认的零消费。潜在供应商费用由平台承担。
- 重复报告不会重复释放额度，也不会清除后来请求的占用。迟到结果不再向新请求扣费。
- Worker 在终止前重新读取持久化回执；存在回执时恢复 OSS 交付与原消费结算，不再生图。回执存储暂不可读则延后恢复。
- 进程直接退出、未能报告终态时，仍由现有十分钟过期回收兜底；正常执行中的请求继续占用其钱包。
- 前端在钱包忙碌或余额事件流未就绪时，每 5 秒独立刷新余额，余额查询 10 秒超时，账号切换和卸载会停止刷新。刷新只查询状态，不提交图片任务。

## 验证与排查

后端运行 `mvn -pl agent-auth-service,agent-media-service -am verify`；前端运行 `npm run test:billing-recovery`，另验证账号切换与图片请求幂等。

定位时以 `image_job.id = model_usage.id` 关联任务与消费，检查 `billing_wallet.active_usage_id`、`usage_reconciliation.reason` 和供应商 request ID。日志不输出 API Key、签名 URL 或原始供应商错误正文。不要删除消费记录或通过把 UNKNOWN 改成 SUCCEEDED、填写零 Token 来解除占用。

若需再次尝试，用户明确提交新请求；不得自动重放结果未知的付费 POST。独立连接降低旧连接复用导致的失败，不能保证外部供应商或网络永不报错。
