# Image Generation and Chat Modes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 支持纯文字生图，清晰区分五种聊天入口，自动路由明确生图要求，并可恢复和幂等计费。

**Architecture:** 复用媒体服务 MySQL 图片任务、租约 Worker、OSS 和现有计费服务。前端统一计算实际模式，在发送前用于准入、附件校验和任务派发；任务请求保存到对应消息并幂等恢复。

**Tech Stack:** React 18 / TypeScript、Java 21 / Spring Boot、MySQL、DashScope、OSS。

**Spec:** ../specs/2026-10-13-image-generation-modes-design.md

## Global Constraints

- 五个入口：智能聊天、文本聊天、图片多模态、文件多模态、图片生成，默认智能聊天。
- 图片单张 20 MiB、文档单份 100 MiB，总计最多 8 个附件、图片最多 3 张；生成只支持 0 或 1 张参考图。
- 计费优先实际 Token，缺失时按现有图片价格折算；不改变账户并发消费锁。
- 使用现有出版克隆，保留原始工作区其他未提交更改；不引入新依赖。

## Review Focus

- 讨论图片生成、否定指令和要求写提示词不能误触发生图（任务 2 正反例）。
- 已有文档/图片会话切换到文本模式应隔离附件上下文（任务 2 hook 测试）。
- 创建请求响应丢失后刷新，不能生成第二张或把任务贴到另一个回复（任务 1 幂等 + 任务 3 恢复测试）。
- 跨用户使用 requestId 或来源资产必须被隔离（任务 1 所有权测试）。
- 图片后台受理、上传中停止和查询暂时失败必须都有明确 UI 出口（任务 3 停止/恢复测试）。

### Task 1: Durable text-to-image API

**Files:** 媒体服务 image/ImageJobService.java、ImageJobController.java、ImageJobWorker.java、provider/DashScopeImageGenerationClient.java、image/OpenAiCompatibleImageGenerationProvider.java；新增 db/migration/V5__media_text_to_image.sql 与 ImageCreationTest、DashScopeTextToImageTest；调整现有合约测试。

**Interfaces:** POST /api/image-jobs 接收 `{requestId,conversationId,prompt,attachments?}` 返回 JobView；GET /api/image-jobs/by-request/{requestId} 按当前用户查询。JobView 增加可选 requestId。内部旧 create 重载保留。

- [ ] 写失败测试：无附件文字创建成功；空 prompt 无图为 400；两图为 400；同 requestId 重放仅创建一次，参数改变 409；用户隔离；Worker 不签名空来源；DashScope 无图只发送 text，有图保留 image。
- [ ] 运行媒体测试确认新行为失败。
- [ ] 添加可空来源、客户端 requestId 与指纹迁移；实现创建接口和幂等重放；调整 Worker 与供应商请求。
- [ ] 运行媒体及相关边界/计费回归，确认通过。

### Task 2: Mode routing and UI

**Files:** 前端新增 src/lib/chatModes.ts；修改 attachments.ts、Composer.tsx、App.tsx、ChatArea.tsx、MessageItem.tsx、types.ts；新增 scripts/test-chat-modes.tsx，更新 test-unified-chat.tsx 中旧模式要求。

**Interfaces:** `ChatMode='chat'|'text'|'vision'|'document'|'image'`；`resolveChatMode(text,files,selected,context)` 返回实际模式/能力；共享路由用于 Composer、App 准入和 hook。

- [ ] 写失败测试：五个选项、用途说明、纯文图片生成可发送；明确生图指令正例；讨论/否定/提示词/代码负例；不丢弃不兼容附件；文本模式隔离历史附件。
- [ ] 运行 test:chat-modes 确认失败。
- [ ] 实现模式元数据、限制与显式意图规则；保存助手消息实际模式并显示可访问标签。
- [ ] 运行新旧前端测试和 typecheck。

### Task 3: Frontend durable image creation

**Files:** 前端新增 src/lib/imageCreation.ts；修改 useConversations.ts、imageJobPolling.ts、api.ts、storage.ts；新增 scripts/test-image-creation.tsx 并更新图片合并测试。

**Interfaces:** 生成请求快照 `{requestId,conversationId,prompt,attachments}`；助手消息持久化 imageRequest，返回 JobView 关联 requestId；成功受理后开始独立任务轮询并释放浏览器 busy。

- [ ] 写失败测试：纯文字进入图片 API 而非 executions；一图创作先上传；重复响应/刷新保留一个任务和原消息；创建响应丢失可查询或幂等恢复；停止不重发、切换账户不发布旧结果；查询暂时失败保留任务。
- [ ] 运行新用例确认失败。
- [ ] 替换同步 zine 发送链路为持久化图片任务；保留历史 zine 回执展示；刷新时按 requestId / jobId 正确合并并有限等待。
- [ ] 运行完整前端 test:* 与 production build；运行后端媒体 reactor 检查。

### Task 4: Review, deployment and publication

**Files:** 两仓 README、spec/plan；.migration 下独立发布与脱敏验收脚本。

- [ ] 独立复核授权、幂等、停止/刷新、模式隔离与计费安全；修复重要问题并运行必要回归。
- [ ] 备份线上数据库、Compose、媒体 JAR、前端静态资源；上传测试产物并核对哈希。
- [ ] 部署媒体和前端，检查迁移与容器健康；失败自动回滚应用产物，保留可空字段迁移。
- [ ] 隔离账户验收纯文字生图、参考图创作、重复请求不加扣费及文本/图片/文档聊天；撤销测试会话并禁用账户。
- [ ] 同步安全源文件到原始工作区、更新 README、提交两个 GitHub 仓库并核对远程 SHA。
