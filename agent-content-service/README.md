# agent-content-service 模块帮助文档

## 1. 模块作用与边界

Content 提供两类能力：把上传图片转为纸刊风格作品的 Zine 创作，以及 Bobo World 作品发布、浏览、编辑和删除。默认端口 `18083`，启动类为 [ContentServiceApplication](src/main/java/com/bbb/exercise/agentdemo/contentservice/ContentServiceApplication.java)。

本模块拥有作品发布表及历史 Zine 回执，负责上传校验和提示词组合。新 Zine 请求通过签名内部接口交给 Media 持久执行，复用其准入、Worker、供应商与结算恢复；Content 不再直接调用生图供应商。

## 2. 代码导航

| 类 / 包 | 职责 |
| --- | --- |
| [ZineController](src/main/java/com/bbb/exercise/agentdemo/contentservice/zine/ZineController.java) | multipart 图片创作和生成结果恢复 API |
| [ZineGenerationService](src/main/java/com/bbb/exercise/agentdemo/contentservice/zine/ZineGenerationService.java) | 上传校验、Base64 输入和确定性请求组合 |
| [ZinePromptCompiler](src/main/java/com/bbb/exercise/agentdemo/contentservice/zine/ZinePromptCompiler.java) | 确定性编译风格、视觉指令和 rationale |
| [ZineResultService](src/main/java/com/bbb/exercise/agentdemo/contentservice/zine/ZineResultService.java) | 转发 Media、合并结果列表并恢复历史回执结算 |
| [BoboWorldController](src/main/java/com/bbb/exercise/agentdemo/contentservice/bobo/BoboWorldController.java) | 作品发布 / 浏览 / 管理入口 |
| [BoboWorldService](src/main/java/com/bbb/exercise/agentdemo/contentservice/bobo/BoboWorldService.java) | 发布幂等、所有权、版本、游标和 OSS 清理 |
| [ZineCompositionProperties](src/main/java/com/bbb/exercise/agentdemo/contentservice/ZineCompositionProperties.java) | 上传大小限制；供应商配置归 Media |

## 3. Zine 创作如何实现

1. 浏览器登录并取得 CSRF，在发送前保存小写 UUID；multipart `requestId` 或 `Idempotency-Key` 必填，两者同时提供时必须一致。
2. 上传 `image`，可选 `mode`、`language`、`text`、`guidance`；Controller 限制读取字节数。
3. `ZineGenerationService` 校验图片类型/大小，解析模式和语言，编译确定性提示词和 rationale。
4. 原图转为 Base64 data URI，通过签名 Content→Media 接口提交；Media 只接受 Content 调用方。
5. Media 按用户、租户、请求 ID 和参数指纹持久化任务，相同请求复用；不同参数复用 ID 返回冲突。
6. Media Worker 记录派发、调用供应商、先保存回执再结算；未知派发不自动重发。
7. Content 等待后返回原 response 形状；断线不会取消已入库任务，可通过同一 ID 查询恢复。

允许 JPEG、PNG、WebP、BMP、TIFF、GIF；最大 20 MiB，可调低。供应商型号、尺寸、超时和水印等参数统一配置在 Media，与 Auth IMAGE 模型对齐。Zine 可在不启用 OSS 时处理，结果仍是供应商 URL。

### 两种创作模式

| mode | 输入含义 | 提示词意图 |
| --- | --- | --- |
| `gathered`（默认） | 实景拼贴 | 保留照片的现场锚点，以纸张、撕边、源图轮廓和克制插画延展画面 |
| `distillation` | 影像蒸馏 | 从图中提取语义与情绪，重新组织为独立插画作品 |

`language` 支持 `en`（默认）、`zh`、`bilingual` 及代码中的别名。当前 PromptCompiler 要求成品不生成文字，`text` 被当作视觉创意方向，而不是必须排版到图片中的文案；不能仅凭字段名就承诺图片会写出指定文字。

“内部 Scene Card / Distillation Card”是提示词要求模型先理解源图的指令，不代表服务已经单独执行并持久化了一次视觉模型分析。rationale 是服务编译得到的风格解释，不是模型思维链。实际画面仍由图片模型生成，布局约束是提示而不是确定性渲染保证。

## 4. Zine 结果恢复

新结果由 Media 的任务和回执保存，`GET /api/zine/generations` 合并新请求与历史 Content 回执 ID（最多 50 个），`GET /api/zine/generations/{id}` 按 owner 恢复。历史 `zine_generation_receipt` 保留原数据和幂等结算行为。

请求未完成时查询返回受控冲突，保留 ID 稍后查询或提交相同输入；不要为自动重试创建新 UUID。已有回执由 Worker 恢复结算，已派发且结果未知的任务不重复生成。结果含供应商 URL，没有自动 OSS 归档；普通图片 archive 不混入 Zine 的无 OSS 任务。

配套前端位于独立仓库，部署前需更新为保存并重用 requestId。完整故障判断见 [图片恢复说明](../agent-media-service/README.md)。

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
| `POST /api/zine/generate` | multipart 提交持久 Zine 任务并等待结果，稳定请求 ID 必填 |
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
| `ZINE_MAX_UPLOAD_BYTES` | `20971520`，可调低 |
| `MEDIA_SERVICE_URL` | Media 内部地址，签名配置需一致 |
| `app.bobo.cleanup-interval` | `60000` 毫秒，可用 Spring 属性覆盖 |

```powershell
# 仓库根目录；准备 MySQL、Auth、Media、Nacos 和相关 OSS / 图片配置
mvn -pl agent-content-service -am '-Dmaven.compiler.fork=true' package
java -jar agent-content-service/target/agent-content-service-0.0.1-SNAPSHOT.jar
```

## 9. 测试、排错和扩展

另开终端执行 `mvn -pl agent-content-service -am '-Dmaven.compiler.fork=true' test`。[测试目录](src/test/java/com/bbb/exercise/agentdemo/contentservice) 覆盖启动配置、schema、Zine 回执恢复和 Bobo 清理契约。

- **模型不一致**：检查 Auth IMAGE 配置与 Media 供应商设置，并检查 Nacos 覆盖。
- **生成超时**：先检查回执列表和消费记录；外部代理超时还应与图片生成耗时匹配。
- **不能发布**：必须来自当前用户已成功的 Media job，且 OSS output 可读取。
- **PATCH 409**：重新获取作品 version，再基于最新值修改。
- **跨域 PATCH 预检失败**：当前 Gateway CORS 方法名单未包含 PATCH，检查同源代理方式和网关方法配置。
- **删除后 cleanup_pending 长期不清**：检查 OSS 配置、对象删除权限和清理日志。
- **匿名浏览被拒绝**：以当前安全链为准，需修改网关与权限策略才能开放，不仅是 Controller。

新增创作模式先扩展 ZineMode 和 PromptCompiler；新增发布来源必须保留来源授权、owner、版本、幂等和资源清理语义。
