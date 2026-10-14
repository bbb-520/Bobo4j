# 可复制的提示词知识库

2026-10-14 新增右上角“提示词”入口。打开全屏图库可以搜索、筛选分类、加载更多，点击卡片查看大图和完整原文；卡片与详情均可一键复制。返回聊天时保留未发送文字、附件和进行中的任务。手机与深色主题均支持。

## 内容和费用

- **精选画廊**：固定收录 GPT-Image2-Skill 的 164 段原文、31 个分类（163 编号，其中一个编号有两段变体），来源 commit `9f8aa1a44a4a5c0f5a5d6e45eed564b3e2d5b87f`。目录在前端 `src/data/prompt-gallery.json`，按需加载，首批24条。
- **我的视觉记忆**：当前登录账号已有的图片描述和创作要求，仅本人可见。不是公开画廊，也没有把用户私人图片、向量或记忆导入公共目录。
- 浏览、搜索、复制都是静态目录或只读数据库请求，不调用模型、Embedding、向量重建或计费接口。既有付费向量画廊开关不会因此打开。用户复制后主动发送聊天/生图，仍按原模型规则计费。
- 封面暂为原创 SVG **风格示意**，不承诺与提示词实际生成效果一致。原提示词标题、正文、作者和出处保留；未声明的尺寸/质量/标签保持为空，不用示意封面补造元数据。

## 私人记忆接口

```http
GET /api/settings/visual-memory/prompts?q=雨后&limit=24&cursor=上一页返回的nextCursor
```

沿用登录会话和 `chat:use` 权限；无需也不接受前端指定用户作为授权依据。SQL 同时过滤认证的 tenant_id、user_id、active=1，按 updated_at、id 倒序游标分页；每页一次 limit+1 查询。默认24条，limit允许1～24，q最多160字符；按正文作文字匹配，`%`、`_` 视作普通字符。当前真实记忆正文已经包含摘要、主体、配色、构图、情绪与用户指令。仅存在于异常旧元数据中的文字不参与搜索。

返回 `{ "items": [...], "nextCursor": "...或null" }`，每条包含 id、title、content、category/source、updatedAt 和有效的白名单展示元数据。游标绑定账号、租户和规范化搜索词；更换搜索词应从第一页开始。响应 `Cache-Control: private, no-store`；未登录401、无权限403、参数错误400。前端账号变更会立即清空旧内容并中止请求，晚到响应不能回填。

## 更换封面和更新目录

前端 `public/prompt-library/covers.json` 用真实提示词 UUID 映射图片：

```json
{"schemaVersion":1,"covers":{"真实提示词UUID":"/prompt-library/covers/my-poster.webp"}}
```

把授权图片放到 `public/prompt-library/covers/` 后重新构建和部署前端；也支持公开 HTTPS 图片。图片读取失败自动回退为示意封面。不要公开私人视觉记忆图片、带签名临时地址或访问密钥。具体步骤在前端 `public/prompt-library/README.md`。

完整目录的离线可复现导出工具见 [ops/prompt-library](../ops/prompt-library/README.md)。工具复用现有 Markdown 解析器，不启动应用或联网调用模型；固定来源指纹、完整数量、稳定 ID、正文哈希和 MIT 归属都会校验。更新上游版本必须同时审核固定 commit、指纹与数量变化。前端分发 `third-party/` 中的 MIT 完整许可和 NOTICE；提示词许可不代替图片使用权。

## 验证

```powershell
# 后端根目录：包含真实安全链/H2所有者隔离、分页、搜索、损坏元数据和零模型调用测试
./mvnw.cmd -o verify

# 前端根目录
npm run test:prompt-library
node e2e/run-unit-tests.mjs
npm run build
npm run test:e2e
```

浏览器验收使用模拟接口，禁止未知外网和模型请求，覆盖全文复制（Windows系统剪贴板会规范化换行）、草稿/附件保留、浏览器前进后退、私有记忆重试、手机布局和深色主题。真实服务器另外检查所有者隔离、无费用变化和发行文件哈希。
