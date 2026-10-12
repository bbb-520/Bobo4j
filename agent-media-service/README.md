# agent-media-service 模块帮助文档

## 1. 模块作用

Media 负责图片资产直传、所有权检查、图片生成任务、供应商适配、结果回执与 OSS 成品存储。默认端口 `18082`，启动类为 [MediaServiceApplication](src/main/java/com/bbb/exercise/agentdemo/mediaservice/MediaServiceApplication.java)。

Chat / Orchestrator 通过内部 HTTP 创建任务，Content 查询可发布结果。Media 拥有 `image_asset` 和 `image_job`，不直接管理聊天历史、作品发布或钱包余额。

## 2. 代码导航

| 代码 | 作用 |
| --- | --- |
| [ImageAssetController](src/main/java/com/bbb/exercise/agentdemo/mediaservice/image/ImageAssetController.java) / [ImageAssetService](src/main/java/com/bbb/exercise/agentdemo/mediaservice/image/ImageAssetService.java) | 申请上传策略、确认上传、所有权与有效期 |
| [ImageJobController](src/main/java/com/bbb/exercise/agentdemo/mediaservice/image/ImageJobController.java) / [ImageJobService](src/main/java/com/bbb/exercise/agentdemo/mediaservice/image/ImageJobService.java) | 任务创建、领取、回执、状态、下载和归档查询 |
| [ImageJobWorker](src/main/java/com/bbb/exercise/agentdemo/mediaservice/image/ImageJobWorker.java) | 后台生成、续租、保存结果、结算与故障恢复 |
| [ImageGenerationProviderRegistry](src/main/java/com/bbb/exercise/agentdemo/mediaservice/image/ImageGenerationProviderRegistry.java) | 供应商路由 |
| [image](src/main/java/com/bbb/exercise/agentdemo/mediaservice/image) 中的 Provider | DashScope / OpenAI 兼容协议适配 |
| [provider](src/main/java/com/bbb/exercise/agentdemo/mediaservice/provider) | Zine 图片调用接口和 DashScope 客户端 |
| [InternalMediaController](src/main/java/com/bbb/exercise/agentdemo/mediaservice/InternalMediaController.java) | 签名内部资产、任务创建和发布源协议 |

## 3. 图片直传流程

1. 浏览器发送 `POST /api/image-assets/upload-policy`，提供 `fileName`、`contentType`、`fileSize`。
2. `ImageAssetService` 生成资产 UUID 和 owner 哈希隔离的 OSS object key，保存 `PENDING` 记录。
3. OSS 返回限时表单策略；浏览器向返回的 `uploadUrl` 提交所有 fields 和文件。
4. 浏览器调用 `POST /api/image-assets/{assetId}/complete`。
5. 服务查询 OSS 元数据，核对大小和 MIME，更新 `READY`。
6. 聊天请求仅引用 assetId；后端再次检查 owner、READY 与有效期。

允许 `image/jpeg`、`image/png`、`image/webp`、`image/gif`，默认最大 20 MiB。原文件名会清理换行和路径字符。资产记录有效期当前为 1 天；上传策略默认 600 秒，二者不是同一时限。

complete 校验的是 OSS 元数据和声明，不应把这一步描述为完整图片内容安全检测。未 complete 的文件不能作为就绪资产使用。

## 4. 生成任务和状态

任务由 Chat / Orchestrator 的 `/internal/media/image-jobs` 创建，参数包括 conversationId、prompt 和可选 assetId。Service 固定任务的供应商、型号、来源 / 输出 object key，校验 prompt 和所有权，并利用数据库约束减少同会话活跃任务的重复提交。

```text
QUEUED → PROCESSING → 保存供应商回执 → 复制结果到 OSS → 结算 → SUCCEEDED
                     ├→ 有回执的暂时错误：延后继续
                     └→ 无明确回执：FAILED / 消费待核对
```

`ImageJobService.claimNext` 通过条件更新领取队列任务或租约过期任务；Worker 续租。完成 / 失败写入带 owner 条件，旧 Worker 不能直接覆盖当前租约。过期队列任务会标记 FAILED。

## 5. Worker、回执和幂等恢复

`ImageJobWorker` 默认开启，每 3000ms 轮询，初始延迟 5 秒；默认租约 5 分钟。OSS 未配置时 Worker 不领取任务，所以应用健康并不代表图片任务会运行。

执行步骤：

1. 领取任务并启动租约心跳。
2. 先查询是否已有持久供应商回执；有则跳过再次生成。
3. 无回执时从 Auth 获取图片模型，确认供应商与任务快照一致。
4. 请求计费预留并登记派发，再调用供应商。
5. **先持久化生成 URL、供应商 request ID 和用量回执**。
6. 将远端图片复制到预定 OSS output key。
7. 按回执结算，并标记 SUCCEEDED。

有回执后复制 / 结算发生临时故障，任务可以延后恢复，不需要重新生成。已派发但未拿到回执时，消费可能 UNKNOWN，当前 Worker 会以一次失败处理并保留待核对语义；不能把 `IMAGE_JOBS_MAX_ATTEMPTS=2` 理解成对所有供应商异常都会自动重新生成两次，Worker 的具体 fail 调用仍控制该行为。

晚到的供应商结果会被保留，供后继租约继续处理。图片任务状态与 Auth 消费状态分属两个边界，任务 FAILED 不等于费用已取消或退款。

## 6. 供应商与 OSS

Provider registry 按 `ModelProvider` 选择实现，生成输出统一为图片 URL、说明、用量与供应商请求 ID。DashScope 和 OpenAI 兼容接口对协议细节分别适配，不能假设每个模型都支持相同的图片能力。

OSS 操作复用 [Runtime 的 OssStorageService](../agent-runtime/README.md)。数据库保存 object key，浏览器读取时生成限时 URL。发布源必须来自当前用户已成功的任务；Content 使用内部 publish-source 协议取得结果。

## 7. HTTP 接口

| 方法与路径 | 用途 |
| --- | --- |
| `POST /api/image-assets/upload-policy` | 获取直传策略 |
| `POST /api/image-assets/{assetId}/complete` | 确认上传完成 |
| `GET /api/image-jobs/{jobId}` | 查询任务和临时结果 URL |
| `GET /api/image-jobs/{jobId}/download` | 获取成功任务下载结果 |
| `GET /api/image-jobs` | 按会话查询任务，参数见 Controller |
| `GET /api/image-jobs/archive` | 当前用户成功图片列表 |
| `GET /internal/media/assets/{assetId}` | 校验就绪资产并返回签名读取 URL |
| `POST /internal/media/image-jobs` | 服务间创建任务 |
| `GET /internal/media/image-jobs/{jobId}/publish-source` | 已成功任务的可发布源 |

浏览器写请求需要 Cookie、CSRF 和 `media:generate`。没有公开的 `POST /api/image-jobs` 创建接口，应走聊天 / Agent 创作流程或受信内部调用。

## 8. 数据和配置

迁移目录：[db/migration](src/main/resources/db/migration)，历史表 `flyway_schema_history_media`。V1 创建图片资产和任务表，V2 加入租约 / 幂等字段，V3 增加生成准入约束，V4 保存供应商回执。

配置入口：[application.yml](src/main/resources/application.yml) 与 [application-worker.yml](src/main/resources/application-worker.yml)。

| 环境变量 | 用途 / 默认 |
| --- | --- |
| `SERVER_PORT` | `18082` |
| `MYSQL_*` / `NACOS_*` | 数据和发现 / 配置 |
| `AUTH_SERVICE_URL` / 内部签名配置 | 模型、身份与计费 |
| `OSS_ENABLED`、`ALIYUN_OSS_*` | 必须准备可用 OSS 才能处理完整图片链路 |
| `OSS_MAX_OBJECT_BYTES` | `20971520` |
| `IMAGE_JOBS_ENABLED` | `true` |
| `IMAGE_JOBS_POLL_INTERVAL` | `3000ms` |
| `IMAGE_JOBS_LEASE_DURATION` | `5m` |
| `IMAGE_WORKER_ID` | `media-worker`，实际 owner 还包含随机值 |
| `ZINE_IMAGE_*`、`PLATFORM_IMAGE_MODEL` | 图片供应商参数 |

`worker` profile 增加调度线程池设置（默认 2）；Worker 组件本身并非只有该 profile 才会启用。

## 9. 构建、测试和排错

```powershell
# 仓库根目录；先准备 MySQL、Auth、Nacos、OSS 和图片模型
mvn -pl agent-media-service -am '-Dmaven.compiler.fork=true' package
java -jar agent-media-service/target/agent-media-service-0.0.1-SNAPSHOT.jar
```

另开终端执行 `mvn -pl agent-media-service -am '-Dmaven.compiler.fork=true' test`。[测试目录](src/test/java/com/bbb/exercise/agentdemo) 覆盖 Worker 回执恢复、租约、Provider 契约、内部边界、启动配置和远程 URI 安全。

- **任务一直 QUEUED**：检查 OSS 配置、Worker enabled、调度日志和其他活跃任务。
- **资产不可用**：检查 owner、complete、MIME / 大小和 1 天有效期。
- **模型变更冲突**：已创建任务保留供应商快照，不能直接用变更后的供应商替代旧任务。
- **结果 URL 过期**：重新查询下载接口取得签名 URL。
- **供应商结果不明确**：保留 jobId 和 Auth 消费记录，通过核对处理，不反复提交新的付费任务。

新增供应商时实现统一 Provider 接口，并验证回执、计费、超时、结果下载和租约接管后不会重复生成。
