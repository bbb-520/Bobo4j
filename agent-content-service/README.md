# agent-content-service 模块帮助文档

## 1. 模块作用与边界

Content 提供两类能力：把上传图片转为纸刊风格作品的 Zine 创作，以及 Bobo World 作品发布、浏览、编辑和删除。默认端口 `18083`，启动类为 [ContentServiceApplication](src/main/java/com/bbb/exercise/agentdemo/contentservice/ContentServiceApplication.java)。

本模块拥有作品发布表和同步 Zine 回执；Media 拥有通用图片资产 / 异步生成任务。Zine 当前有自己的同步供应商适配路径，不是所有创作请求都会转成 Media 图片任务。

## 2. 代码导航

| 类 / 包 | 职责 |
| --- | --- |
| [ZineController](src/main/java/com/bbb/exercise/agentdemo/contentservice/zine/ZineController.java) | multipart 图片创作和生成结果恢复 API |
| [ZineGenerationService](src/main/java/com/bbb/exercise/agentdemo/contentservice/zine/ZineGenerationService.java) | 输入校验、Base64 / URL 输入和供应商生成 |
| [ZinePromptCompiler](src/main/java/com/bbb/exercise/agentdemo/contentservice/zine/ZinePromptCompiler.java) | 确定性编译风格、视觉指令和 rationale |
| [ZineResultService](src/main/java/com/bbb/exercise/agentdemo/contentservice/zine/ZineResultService.java) | 保存生成回执并恢复结算 |
| [DashScopeImageGenerationClient](src/main/java/com/bbb/exercise/agentdemo/contentservice/zine/DashScopeImageGenerationClient.java) | DashScope 多模态图片协议 |
| [BoboWorldController](src/main/java/com/bbb/exercise/agentdemo/contentservice/bobo/BoboWorldController.java) | 作品发布 / 浏览 / 管理入口 |
| [BoboWorldService](src/main/java/com/bbb/exercise/agentdemo/contentservice/bobo/BoboWorldService.java) | 发布幂等、所有权、版本、游标和 OSS 清理 |
| [ZineProperties](src/main/java/com/bbb/exercise/agentdemo/contentservice/ZineProperties.java) | 图片模型、尺寸、超时和上传限制 |

## 3. Zine 创作如何实现

1. 浏览器登录并取得 CSRF，上传 multipart 字段 `image`，可选 `mode`、`language`、`text`、`guidance`。
2. Controller 获取 Auth 的 IMAGE 模型配置，并限制读取上传字节数。
3. `ZineGenerationService` 校验文件类型 / 大小，解析模式和语言，编译确定性提示词。
4. 原图转成 `data:<mime>;base64,...`；供应商客户端接收图片与提示词。
5. `BillingClient.image` 先预留、登记派发，再订阅真正的供应商请求。
6. 供应商返回后，`ZineResultService` **先保存结果回执，再完成结算**。
7. 返回 requestId、生成 URL、模式、模型、rationale、供应商请求 ID 和用量。

允许 JPEG、PNG、WebP、BMP、TIFF、GIF；默认最大 20 MiB。默认图片型号 `qwen-image-3.0-pro`，Auth 的 IMAGE model 必须与本模块 Zine model 一致。

### 两种创作模式

| mode | 输入含义 | 提示词意图 |
| --- | --- | --- |
| `gathered`（默认） | 实景拼贴 | 保留照片的现场锚点，以纸张、撕边、源图轮廓和克制插画延展画面 |
| `distillation` | 影像蒸馏 | 从图中提取语义与情绪，重新组织为独立插画作品 |

`language` 支持 `en`（默认）、`zh`、`bilingual` 及代码中的别名。当前 PromptCompiler 要求成品不生成文字，`text` 被当作视觉创意方向，而不是必须排版到图片中的文案；不能仅凭字段名就承诺图片会写出指定文字。

“内部 Scene Card / Distillation Card”是提示词要求模型先理解源图的指令，不代表服务已经单独执行并持久化了一次视觉模型分析。rationale 是服务编译得到的风格解释，不是模型思维链。实际画面仍由图片模型生成，布局约束是提示而不是确定性渲染保证。

## 4. Zine 结果恢复

`zine_generation_receipt` 保存当前用户的结果 JSON。`GET /api/zine/generations` 返回最近最多 50 个回执 ID，`GET /api/zine/generations/{id}` 按 owner 查询结果并再次尝试幂等结算。

回执已写入但结算响应丢失时，可以恢复原结果而不用再次生成。结果包含供应商 URL，当前这条同步路径没有自动转存成 Media 的 OSS job 输出，因此不要把它理解为永久 OSS 归档。

每次 POST generate 由服务生成新的随机 requestId；它没有公开接收调用方幂等键。浏览器超时后应先查看已有生成列表 / 消费状态，不应认为重复 POST 会自动复用上一次生成。无回执的供应商未知结果仍需要计费核对。

## 5. Bobo World 发布流程

1. 提交 `jobId`、可选 `caption`、`anonymous`。
2. Content 使用签名内部 HTTP 向 Media 获取当前用户成功任务的 publish-source。
3. 查询是否已发布同一来源；活跃作品重复发布返回原结果，已删除来源不允许再次发布。
4. 检查 OSS 对象存在、大小有效且 Content-Type 为图片。
5. 保存 `bobo_world_item`，记录来源 job、object key、prompt 快照、展示名快照和版本。

默认 `anonymous=true`、`visibility=PUBLIC`、`status=ACTIVE`、`version=1`。caption 最多 500 个 code point。数据库唯一键处理双击并发，避免同一来源重复发布。

发布持有 Media 结果的 object key，不自动创建独立归档图片。发布是新增作品授权 / 展示记录，不能把它描述成任意外部 URL 发布接口。

## 6. 浏览、编辑、删除

- 世界列表查询 PUBLIC + ACTIVE 作品，默认 24 条，最大 48 条；按时间和 ID 的游标分页。
- “我的作品”按当前用户和租户查询，返回下页 cursor 和公开作品数量。
- 修改需要 `version`，同时检查 owner 和当前版本，成功后递增；冲突时重新读取。
- 删除先标记 DELETED 和 `cleanup_pending=1`，数据库提交后后台清理 OSS 对象。
- 清理默认每 60 秒执行，初始延迟 30 秒，每批最多 50 项；OSS 未配置时跳过，失败保留 pending 再试。
- 公开展示图片 / 缩略图使用约 5 分钟的签名 URL，重新读取可刷新 URL。

Controller 中有面向公开浏览的查询方法，但当前 Gateway 和 Runtime PermissionPolicy 对 `/api/bobo/**` 仍要求登录 / `content:manage`。不能根据 Controller 注释就认为匿名访问已开放。

## 7. API 一览

| 方法与路径 | 用途 |
| --- | --- |
| `POST /api/zine/generate` | multipart 同步 Zine 创作 |
| `GET /api/zine/generations` | 当前用户生成回执 ID 列表 |
| `GET /api/zine/generations/{id}` | 恢复结果与幂等结算 |
| `POST /api/bobo/items` | 从成功 Media job 发布，返回 201 |
| `GET /api/bobo/world` | 世界列表，参数 limit / cursor |
| `GET /api/bobo/items/mine` | 当前用户作品 |
| `GET /api/bobo/items/{itemId}` | 作品详情 |
| `PATCH /api/bobo/items/{itemId}` | 修改 caption / anonymous / visibility，version 必填 |
| `DELETE /api/bobo/items/{itemId}` | 软删除与排队清理，返回 204 |

发布 JSON 示例：

```json
{"jobId":"<当前用户已成功的Media任务ID>","caption":"午后的纸上风景","anonymous":true}
```

## 8. 数据、配置和运行

迁移目录：[db/migration](src/main/resources/db/migration)，历史表 `flyway_schema_history_content`。V1 创建 `bobo_world_item`，V2 加入 `source_prompt`，V3 创建 `zine_generation_receipt`。

[application.yml](src/main/resources/application.yml) 配置 MySQL、Nacos、Auth / Media 地址、内部签名、OSS，以及：

| 配置 | 默认值 / 用途 |
| --- | --- |
| `SERVER_PORT` | `18083` |
| `ZINE_ENABLED` | `true` |
| `ZINE_IMAGE_BASE_URL` | `https://dashscope.aliyuncs.com/api/v1` |
| `ZINE_IMAGE_ENDPOINT` | `/services/aigc/multimodal-generation/generation` |
| `PLATFORM_IMAGE_MODEL` | `qwen-image-3.0-pro`，需与 Auth 对齐 |
| `ZINE_IMAGE_SIZE` | `1024*1707` |
| `ZINE_IMAGE_TIMEOUT` | `180s` |
| `ZINE_MAX_UPLOAD_BYTES` | `20971520` |
| `ZINE_PROMPT_EXTEND` / `ZINE_WATERMARK` | `true` / `false` |
| `app.bobo.cleanup-interval` | `60000` 毫秒，可用 Spring 属性覆盖 |

```powershell
# 仓库根目录；准备 MySQL、Auth、Media、Nacos 和相关 OSS / 图片配置
mvn -pl agent-content-service -am '-Dmaven.compiler.fork=true' package
java -jar agent-content-service/target/agent-content-service-0.0.1-SNAPSHOT.jar
```

## 9. 测试、排错和扩展

另开终端执行 `mvn -pl agent-content-service -am '-Dmaven.compiler.fork=true' test`。[测试目录](src/test/java/com/bbb/exercise/agentdemo/contentservice) 覆盖启动配置、schema、Zine 回执恢复和 Bobo 清理契约。

- **模型不一致**：同步修改 Auth IMAGE 配置和 Content Zine model，并检查 Nacos 覆盖。
- **生成超时**：先检查回执列表和消费记录；外部代理超时还应与图片生成耗时匹配。
- **不能发布**：必须来自当前用户已成功的 Media job，且 OSS output 可读取。
- **PATCH 409**：重新获取作品 version，再基于最新值修改。
- **跨域 PATCH 预检失败**：当前 Gateway CORS 方法名单未包含 PATCH，检查同源代理方式和网关方法配置。
- **删除后 cleanup_pending 长期不清**：检查 OSS 配置、对象删除权限和清理日志。
- **匿名浏览被拒绝**：以当前安全链为准，需修改网关与权限策略才能开放，不仅是 Controller。

新增创作模式先扩展 ZineMode 和 PromptCompiler；新增发布来源必须保留来源授权、owner、版本、幂等和资源清理语义。
