# Spring AI 提示词画廊 RAG

本功能把 GPT-Image2-Skill 的画廊 Markdown 解析成 Spring AI `Document`，用 `EmbeddingModel` 向量化提示词正文，存入独立的真实 Milvus collection，再通过 `VectorStore.similaritySearch` 按自然语言和分类检索。实现位于 `agent-rag-service` 的 `com.bbb.exercise.agentdemo.ragservice.gallery` 包；Java 21 / Spring Boot 4.1.1 / Spring AI 2.0.1，沿用项目 Milvus SDK 2.6.4。

这是可运行的**管理员平台成本示例**。现有普通用户模型执行走计费账本；画廊的独立 OpenAI fallback 尚未接入该账本，因此 import、search、adapt 均要求可信浏览器会话、`ADMIN` 或 `SUPER_ADMIN` 角色及 `model:manage` 权限。现有 `/api/documents/**` 路由还要求 `rag:document:manage`。普通用户不能通过此接口绕过钱包。未接入聊天 UI 或 Agent tool registry。

功能默认关闭，不自动导入，不在应用启动时连接 Milvus、探测模型维度或调用模型。开启功能但没配置 API key 仍能启动；首次需要模型时返回 503，提示配置 `OPENAI_API_KEY` 或提供平台模型 bean。dry-run 只读本地 Markdown，不要求模型 key。

## 来源与归属

- 仓库：<https://github.com/wuyoscar/GPT-Image2-Skill>
- 本次固定 commit：`9f8aa1a44a4a5c0f5a5d6e45eed564b3e2d5b87f`
- 入口：`skills/gpt-image/references/gallery.md`，实际引用 31 个 `gallery-*.md` 分类文件。
- 实际解析结果：163 个编号条目、164 个提示词正文、0 重复、327 条缺失字段提示。No.54 包含原提示词和额外改写提示词，分别形成两个 Document。上游多数未声明 `quality`、`tags`，保留空值并报告，不能把图像像素尺寸当作质量或捏造标签。
- 仓库许可证：MIT，Copyright (c) 2026 Wuyoscar；完整许可保存在 [third-party/GPT-Image2-Skill.LICENSE](third-party/GPT-Image2-Skill.LICENSE)。画廊里的 `Curated`、`Author`、`Source` 标记另行保留；不表示全部案例由仓库作者创作。
- 上游文件仅作为数据读取。不执行其 SKILL.md、脚本、hooks、命令或远程图片请求。不把图片二进制纳入向量库。运行时读取 `.git/HEAD`/refs 校验声明的 source commit；源码目录由管理员配置，不接受 HTTP 路径参数。使用未修改的固定 checkout，Document 的 `content_hash` 同时记录正文摘要。

## Document 与隔离策略

`Document.text` 只含提示词正文。CommonMark 先识别代码围栏，保留围栏内部的多级标题、列表和 JSON；删除图片 Markdown/HTML，不包含导航、分类介绍、输出 QA 和元数据。一个条目含多个完整提示词围栏时分别入库。显式 `Prompt` 小标题下的非围栏正文也支持；未闭合围栏、无提示词、非提示词代码围栏和重复正文均有解析诊断。

metadata 包含 `category`（分类文件名 slug，例如 `watercolor`）、`source_file`、`title`、`variant`、`size`、`quality`、`tags`、`image_urls`、`source_repository`、`source_commit`、`license`、`attribution`、`content_hash`，以及可用的 `author`、`source_attribution`、`curated`、`orientation`、`raw_metadata`。数组直接存入 Milvus JSON metadata 字段。`image_urls` 保留上游图片路径/链接，可能是仓库相对路径；本服务不会访问它们。

稳定 ID 由 `category + 换行 + 规范化正文` 计算，标题修改/文件移动/commit 更新不会改变相同正文的 ID；同分类重复正文在解析时去重。存储使用 Milvus **upsert**，重复导入不会增加重复行。重复导入仍会重新生成 embedding，仍可能收费；本次实现没有声称提供跨导入的 embedding 缓存。正文变化产生新 ID；导入不会自动清除旧 ID，更新全量目录时请切换新 collection 后导入。

collection 必须以 `agent_prompt_gallery_` 开头，默认 `agent_prompt_gallery_openai_small_1536_v1`，与用户 RAG 的 `agent_rag_v2` 完全隔离。schema description 记录 embedding-space/base-url/model/dimensions 的 SHA-256；不匹配时拒绝混用，要求新 collection。切换供应商、模型、版本或维度必须更换 `PROMPT_GALLERY_EMBEDDING_SPACE` 和 collection 并重新导入。提供平台 `EmbeddingModel` 时也必须显式描述其实际模型空间。

## 配置与运行

先准备固定源（不要运行上游任何脚本）：

```powershell
git clone --depth 1 https://github.com/wuyoscar/GPT-Image2-Skill.git D:/data/GPT-Image2-Skill
git -C D:/data/GPT-Image2-Skill fetch --depth 1 origin 9f8aa1a44a4a5c0f5a5d6e45eed564b3e2d5b87f
git -C D:/data/GPT-Image2-Skill checkout --detach 9f8aa1a44a4a5c0f5a5d6e45eed564b3e2d5b87f
```

在项目正常配置 MySQL、Redis、Auth、Nacos/Milvus 等现有依赖的基础上，额外启用 `gallery` profile。打包后启动示例：

```powershell
$env:JAVA_HOME='D:/jdk21'
$env:SPRING_PROFILES_ACTIVE='gallery'
$env:PROMPT_GALLERY_ENABLED='true'
$env:PROMPT_GALLERY_SOURCE_ROOT='D:/data/GPT-Image2-Skill'
$env:PROMPT_GALLERY_SOURCE_COMMIT='9f8aa1a44a4a5c0f5a5d6e45eed564b3e2d5b87f'
$env:MILVUS_URI='http://127.0.0.1:19530'
# OPENAI_API_KEY 从部署密钥/进程环境注入，不写入仓库、日志或前端。
# 可选 OPENAI_BASE_URL 默认 https://api.openai.com/v1。
java -jar agent-rag-service/target/agent-rag-service-0.0.1-SNAPSHOT.jar
```

`application-gallery.yml` 包含完整环境映射。默认 embedding 为 `text-embedding-3-small`、1536 维；可通过 `PROMPT_GALLERY_EMBEDDING_MODEL`、`PROMPT_GALLERY_DIMENSIONS`、`PROMPT_GALLERY_EMBEDDING_SPACE`、`PROMPT_GALLERY_COLLECTION` 配置。可选生成需要另设 `PROMPT_GALLERY_CHAT_ENABLED=true`；默认 chat model 为 `gpt-4.1-mini`，可用 `PROMPT_GALLERY_CHAT_MODEL` 替换为账号可用模型。`MILVUS_TOKEN` 也仅从环境注入。

现有唯一 `EmbeddingModel` 和 `ChatModel` bean 优先使用；画廊从 `ChatModel` 创建隔离的 `ChatClient`，避免继承已有业务 ChatClient 的工具、会话 memory 和执行 advisor。无平台模型 bean 时懒创建 `OpenAiEmbeddingModel`/`OpenAiChatModel`，embedding 显式 `MetadataMode.NONE`。不依赖自动配置 starter，不影响现有 RAG 启动。绑定到多个模型 bean 时返回明确错误；请给画廊提供唯一模型，避免误选。本实现不注册工具。Spring AI 2.0.1 的 `ToolCallingChatOptions` 已没有旧版 `internalToolExecutionEnabled`/`toolNames` 接口；隔离 Client 的测试证明不会继承带 default tools/advisors 的业务 Client。

## HTTP 使用例

通过现有 gateway 或服务地址调用 `/api/documents/prompt-gallery/**`。复用现有 Cookie 登录/会话、角色授权和 CSRF；`X-User-ID`/`X-Roles` 等自报请求头不能授予权限。管理员需同时拥有 `rag:document:manage`、`model:manage`；角色默认没有这些权限时，通过现有角色管理为管理员配置，不能临时关闭鉴权。

下面 shell 示例使用现有已登录管理员 cookie jar。写请求中的 XSRF token 必须与 cookie jar 中 `XSRF-TOKEN` 一致：

```bash
# 预览：默认 dryRun=true，不调用 embedding/chat，不写 Milvus。
curl -b admin-cookies.txt -H "X-XSRF-TOKEN: $XSRF_TOKEN" \
  -X POST 'http://localhost:18000/api/documents/prompt-gallery/import?dryRun=true'

# 显式付费 embedding + 真实 Milvus upsert；先检查 dry-run 的 entries/parsedPrompts/issues。
curl -b admin-cookies.txt -H "X-XSRF-TOKEN: $XSRF_TOKEN" \
  -X POST 'http://localhost:18000/api/documents/prompt-gallery/import?dryRun=false'

# embedding 查询 + 向量检索，query 最多2000字符，topK 1..20，category最多128字符。
curl -b admin-cookies.txt -H "X-XSRF-TOKEN: $XSRF_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"query":"雨天温室，柔和水彩，植物和暖色灯光","topK":5,"category":"watercolor"}' \
  'http://localhost:18000/api/documents/prompt-gallery/search'

# 可选手动 rewrite -> search -> bounded context -> ChatClient；rewrite=false省去一次chat调用。
curl -b admin-cookies.txt -H "X-XSRF-TOKEN: $XSRF_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"request":"雨天温室里的柔和水彩植物插画","topK":3,"category":"watercolor","rewrite":true}' \
  'http://localhost:18000/api/documents/prompt-gallery/adapt'
```

import 返回 `dryRun/categories/entries/parsedPrompts/upserted/duplicates/sourceCommit/issues`；missing-field 诊断不阻止有正文的条目入库。导入每批16条，串行执行，重试可能重复模型费用但不会重复行。partial import 出错时此前批次已保留，可修复配置后重试，不声称跨批事务原子性。

search 使用 POST JSON 并校验 CSRF，避免预加载 GET 触发模型费用，返回 `id/prompt/metadata/score`。分类通过 `Filter.Expression` 构造，并由 adapter 使用 Milvus `filterTemplateValues` 参数绑定；类别文本不能注入过滤表达式。adapt 仅将有界参考正文放在 JSON user message 中，固定 system prompt 标明引用数据不可信，使用 `NoOpTemplateRenderer` 防止花括号被模板解释；最多12,000参考字符，每条最多4,000。它输出新提示词文本，不会生成图片，也不会调用图片链接。

所有付费 REST 操作使用进程级保护：默认并发2、每分钟最多10次，分别由 `PROMPT_GALLERY_MAX_CONCURRENT`（1..8）、`PROMPT_GALLERY_MAX_REQUESTS_PER_MINUTE`（1..60）配置。超限返回429。一次 import 可能包含多个模型批次，因此次数限制不等同 token/金额预算；该示例仅允许管理员承担平台成本。多实例部署会各自计数，接入普通用户前需复用正式计费执行上下文并增加分布式额度限制。

## 验证与实际执行边界

单元测试覆盖 Markdown 清理/变体/稳定ID/诊断、持久化 SDK upsert/JSON数组、安全过滤、边界验证、关闭配置/缺key/复用平台模型、可信身份/管理员权限、ChatClient上下文隔离及频率保护。

```powershell
$env:JAVA_HOME='D:/jdk21'
$mvn='C:/Users/bbb/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn.cmd'
& $mvn -ntp -pl agent-rag-service -am test '-Dtest=*Gallery*Test' '-Dsurefire.failIfNoSpecifiedTests=false'

# 加载实际本地上游目录，验证31分类/163编号/164正文；无模型调用。
& $mvn -ntp -pl agent-rag-service -am test '-Dtest=*Gallery*Test' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dgallery.catalog=D:/data/GPT-Image2-Skill'

# 可选真实Milvus集成：只用确定性本地EmbeddingModel，临时collection，finally清理。
$env:PROMPT_GALLERY_MILVUS_INTEGRATION='true'
$env:MILVUS_URI='http://127.0.0.1:19530'
& $mvn -ntp -pl agent-rag-service -am test '-Dtest=GalleryMilvusIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false'
Remove-Item Env:PROMPT_GALLERY_MILVUS_INTEGRATION
```

本次已经执行实际 Markdown 全目录解析和真实本地 Milvus 集成测试；Milvus 持久化/筛选的向量来自确定性本地测试模型。没有执行 OpenAI 语义 embedding、正式画廊全量向量导入或真实 chat 生成，因为当前未提供模型 API key。启用并完成上面的显式 import 后才能声明语义画廊可检索；测试向量不能证明语义检索质量。
