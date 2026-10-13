# 可靠性与扩展改造（2026-10-13）

本次源码改造包含退款重试、模型快照、聊天恢复、工具注册、视觉语义检索及限流熔断。提示词画廊的配置、导入和使用示例见 [prompt-gallery.md](prompt-gallery.md)。这些代码需要重新构建并发布服务；本次开发没有直接更新线上容器。

## 会话与附件恢复

新增 chat 服务的 `conversation_snapshot` 表（Flyway V4），按租户和用户隔离浏览器会话视图。`GET /api/conversations?offset=0&limit=100` 返回分页目录，`GET /api/conversations/{id}` 返回快照，`PUT /api/conversations/{id}` 使用 `expectedRevision` 比较并更新。首次写入版本为0，成功后版本递增；旧版本写入返回409。删除使用 `deleted=true` 墓碑，删除过的ID不能再恢复为有效会话。

前端启动时合并本地缓存和服务器快照，修改后防抖同步。同步中断或冲突会提示并暂停本页同步，保留本地记录；刷新后重试。切换账户会取消原账户的请求并阻止迟到回调。恢复已有执行时使用原 `executionId` 查询，不创建新的模型任务。快照只用于恢复界面，不参与鉴权、消费结算或作为可信工具回执。

单条会话同步上限为1 MiB、2000条消息，目录每页最多100项。历史上从未同步、也没有其他服务端记录的浏览器内容，清缓存后无法凭空恢复；未完成上传的文件二进制仍需用户重新选择。最后一次浏览器修改若尚未完成同步就关闭页面，可能只存在本地缓存。

新增 `GET /api/image-assets/{assetId}/preview`：只有当前账户所属、状态为READY的资产可以获取新的短期OSS签名。上传策略到期不妨碍查看已验证的资产。前端刷新和签名即将到期时重新获取预览；签名地址不会写入本地或远端快照。

浏览器存储写入失败现在会显式报错。新一轮消息和恢复标识保存成功后，才允许上传、发送模型请求或创建图片任务；这也覆盖“旧记录能保存，新增消息刚好超过容量”的情况。允许的参考图格式统一为JPEG、PNG、WebP、GIF，BMP/TIFF需先转换。

## 退款与图片模型

auth 服务的 Flyway V11 保留原退款记录，增加 `attempt_no` 和 `request_key`，同订单允许多个有序尝试。管理员创建退款时可传入稳定的 `requestId`；相同ID返回原尝试，确认FAILED后使用新ID才创建新尝试。PENDING、REVIEW或其他非失败状态继续复用原记录，不能用新ID重复发送不明退款。退款预扣、释放与成功账本事件按退款ID去重，避免第二次尝试冲突或重复加减余额。

该接口仍只处理已到账支付宝订单的全额原路退款，不能退款已消费或预留资金。真实渠道资金、商户权限和退款闭环需独立验收，单元测试不能证明渠道实际到账。

管理员可查询 `GET /api/payments/admin/orders/{id}/refund/history`。调用 `POST /api/payments/admin/orders/{id}/refund` 时请求体例如 `{"reason":"商户问题解决后再次退款","requestId":"一次尝试的稳定UUID"}`，并携带管理员Cookie和CSRF头；网络重试沿用该UUID，确认FAILED后的新尝试才换UUID。

DashScope图片调用现在使用任务中保留的模型快照，实际请求与额度预留、消费审计记录使用同一个模型，避免被全局默认模型覆盖。

## 工具扩展

`ExecutionToolRegistry` 注册Spring AI `ToolCallback`，`ExecutionWorker`通过注册表分派，不再包含五个固定工具的switch。扩展可提供 `ExecutionToolRegistry.Registration` Bean，声明工具名、描述、参数白名单和内部请求计划。决策提示及工具名白名单来自同一注册表。

回调只能在包含已认证身份、会话范围和执行账本的上下文中调用；直接调用回调会被拒绝。工具仍经过执行步骤的预算、租约、派发、回执和恢复流程。文档分节读取仅允许此前实际检索出的文档；图片工具仍要求明确授权。本改造没有增加MCP客户端，不能据此宣称已经支持任意远程MCP服务。

## 视觉语义检索

旧的正则分词和contains打分改为真实EmbeddingModel向量与余弦相似度，向量保存在MySQL `vision_memory.embedding_json`。Flyway V5增加模型空间指纹和维度等索引信息；查询严格按租户和用户过滤，排除不兼容或损坏向量。模型调用复用已有 `ModelCallClient` 计费、回执和未知结果保护。

这是最多扫描1000条当前账户候选的语义检索，不是Milvus视觉ANN索引。大规模视觉记忆应另行迁移到带所有权过滤的向量索引；现有文档RAG和新画廊使用各自Milvus collection。

识别图片时先保存未索引记忆，不隐式新增embedding费用。旧记录和新记忆需显式 `POST /api/settings/visual-memory/reindex?limit=4` 重建，每次1～8条，受现有会话、权限和CSRF保护。未知调用保留原标识和账本证据，不能直接重新派发。模型或维度改变后需重新索引，旧空间不会与新空间混检。

配置在 chat 服务 `app.vision-memory`：`VISION_MEMORY_DIMENSIONS` 默认1024，`VISION_MEMORY_SIMILARITY_THRESHOLD` 默认0.55。embedding模型与凭据通过现有模型管理配置。

## 限流与熔断

gateway配置实际Sentinel路由规则，超限返回429和 `Retry-After`，不会继续调用下游。默认每实例每路由50 QPS；images、documents为20，orchestrator为30。环境变量为 `GATEWAY_TRAFFIC_ENABLED`、`GATEWAY_ROUTE_QPS`、`GATEWAY_RATE_INTERVAL_SECONDS`、`GATEWAY_IMAGE_QPS`、`GATEWAY_DOCUMENT_QPS`、`GATEWAY_EXECUTION_QPS`。也可在Nacos的gateway配置中覆盖 `app.traffic.routes.<路由名>`；控制台规则更新可覆盖启动规则。

Nacos或环境变量中的这些值是启动规则，修改后重启gateway生效；运行中实时调整应使用Sentinel控制台发布规则。限流测试已验证下游只收到获准的一次请求，超限请求不会继续派发。

这些是单gateway实例的路由限流，不等于跨节点或按用户的全局配额。现有模型层自定义 `ModelCircuit` 原本已存在，本次把阈值、窗口、熔断时长和探测租约改为可配置，并补足实际模型边界及指标验证；没有为了使用注解而更换成另一套熔断器。

模型服务中可配置：

```yaml
app:
  model:
    circuit:
      failure-threshold: 5
      window-seconds: 60
      open-seconds: 30
      probe-seconds: 30
```

Redis可用时共享模型熔断状态，故障时使用进程内有限熔断。相关指标包括 `gateway.traffic.blocked`、`ai.model.circuit.admission`、`ai.model.circuit.results`、`ai.model.circuit.redis.errors`，可在各服务 `/actuator/prometheus` 查看对应Prometheus名称；现有请求观测和trace继续保留。

标准Compose已透传上述视觉/限流参数。熔断的Compose变量为 `MODEL_CIRCUIT_FAILURE_THRESHOLD`、`MODEL_CIRCUIT_WINDOW_SECONDS`、`MODEL_CIRCUIT_OPEN_SECONDS`、`MODEL_CIRCUIT_PROBE_SECONDS`，分别映射到上面的四个Spring配置项。修改环境后需重建对应容器。公开且不含真实凭据的模板见 [model-routing.env.example](../config/model-routing.env.example)。

## 验证边界

模型供应商连接reset的根因不能仅凭客户端异常判断。新增本地故障fixture验证“供应商已读完请求再断开”时不会重发付费POST，未知消费不会按0结算。

只读历史测试账户账本确认了文档评估的格式故障：facts模型返回一段解释，末尾才输出合法JSON。现在接受一个末尾、独立成行的判决JSON或单个JSON围栏，继续严格检查字段、布尔类型、claims字符串、重复键及判定一致性；多个判决、内联引用、截断与非法结构仍拒绝。没有新增付费重试，原调用标识和真实用量保持不变。该格式故障已由原始回执复现并修复，生产付费问答仍未重新验收。

提示词画廊使用真实本地Milvus测试持久化、upsert与metadata过滤，模型部分使用可控fixture；未调用真实OpenAI做全量embedding导入。服务器扩容、SSH/Nacos安全配置、真实支付和模型验收仍属独立运维事项。

最终验证：完整后端先执行 `mvn -ntp -fae clean verify -Dmaven.compiler.fork=true`（附带真实源目录和Milvus集成开关），全部模块成功。最后两处RAG修复合并后执行 `mvn -ntp -pl agent-rag-service -am verify`，RAG为125项、0失败/错误、2项环境跳过。最终各模块报告合计557项、552通过、5项环境跳过、0失败/错误；无基线排除。前端16组测试与生产构建全部通过，Compose默认配置及可选画廊配置通过 `config --quiet` 校验。
