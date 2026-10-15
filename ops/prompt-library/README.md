# 完整提示词画廊导出

此工具使用现有 `GalleryMarkdownParser` 从固定来源导出静态目录，不启动 Spring 应用、数据库、向量库或模型，不联网、不导入索引，也不执行上游仓库中的脚本或技能。

固定来源为 <https://github.com/wuyoscar/GPT-Image2-Skill> 的 `9f8aa1a44a4a5c0f5a5d6e45eed564b3e2d5b87f`。完整结果为 **31 个分类、163 个编号条目、164 段提示词**，其中一个编号含两个正文变体。所有条目来自实际解析的 `Document`，不使用手写种子数据。

## 运行

依赖 Java 21、PowerShell，以及已构建并包含现有画廊解析器的 RAG Spring Boot fat jar。测试另需 Node（只使用内置测试库）。不需要 Maven install。

示例假定 Bobo4j、Bobo_web、GPT-Image2-Skill 位于同一父目录，在 Bobo4j 根目录运行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\ops\prompt-library\export-catalog.ps1 `
  -SourceRepository ..\GPT-Image2-Skill `
  -OutputCatalog ..\Bobo_web\src\data\prompt-gallery.json `
  -LicenseDirectory ..\Bobo_web\public\prompt-library\third-party `
  -RagJar .\agent-rag-service\target\agent-rag-service-0.0.1-SNAPSHOT.jar `
  -JavaHome $env:JAVA_HOME
```

同一命令追加 `-Check` 可重新离线解析并逐字节检查目录和两份许可文件；检查失败不会改写这些产物。输出路径为显式参数，导出前请确认路径。脚本只把解析器类和编译所需的 Spring AI Document 依赖解到本目录忽略的 `build/`，随后用 `PropertiesLauncher` 调用独立 CLI 的 `main`，不调用应用入口。

完整测试：

```powershell
$env:PROMPT_LIBRARY_FRONTEND = (Resolve-Path ..\Bobo_web).Path
$env:PROMPT_GALLERY_SOURCE = (Resolve-Path ..\GPT-Image2-Skill).Path
$env:PROMPT_GALLERY_RAG_JAR = (Resolve-Path .\agent-rag-service\target\agent-rag-service-0.0.1-SNAPSHOT.jar).Path
$env:PROMPT_GALLERY_JAVA_HOME = $env:JAVA_HOME
node --test .\ops\prompt-library\tests\export-catalog.test.mjs
```

可通过 `PROMPT_GALLERY_SOURCE`、`PROMPT_GALLERY_RAG_JAR`、`PROMPT_GALLERY_JAVA_HOME` 环境变量覆盖测试依赖位置。默认使用相邻 `reference`、`publish-backend` 和 `D:/jdk21`。本工具目录的完整测试覆盖：164/31 完整性、163 编号与变体、精确 JSON 字段、逐段原文、含注释 JSON/长正文/换行、稳定 ID、每条 SHA-256、作者出处、真实空元数据、MIT 原文、确定性、错误来源版本、来源被修改和损坏产物的只读检查。测试夹具仅写入被忽略的 `build/test-fixtures/`。

## 数据与保真

- `frontend/src/data/prompt-gallery.json` 是完整公开目录。顶层只有 `source`、`categories`、`prompts`；每个提示词字段与实现计划的 Shared contracts 一致。
- `content` 完整采用现有解析器的正文；解析器把 CRLF 标准化为 LF、去掉代码围栏及最外层空白。正文中的 JSON、注释、段落换行、符号和中文不做摘要或翻译。复制时应直接使用此字段。
- `id` 沿用解析器的类别＋正文 UUID v3；`variant` 沿用原始变体编号；`contentHash` 是正文 UTF-8 的 SHA-256。输出顺序沿用上游分类索引和正文顺序，JSON 使用 UTF-8 无 BOM、LF 和末尾换行，重复导出逐字节相同。
- 尺寸、质量、标签、作者和出处只来自实际 `Document` 元数据；未声明字段保持空字符串或空数组。当前源没有显式 tags；不能用正文中的词语、封面尺寸或中文搜索别名补造原始标签。当前解析有 327 条 `MISSING_FIELD` 诊断，均为真实缺失字段；其他诊断、重复、缺分类或少正文都会阻止导出。
- `categories.zh-CN.tsv` 提供 31 个中文显示名称和编辑的搜索关键词，与原始 `tags` 分开。上游标题和正文保持原样。
- `source-fingerprints.sha256` 覆盖上游 LICENSE、索引和 31 个分类文件；对 CRLF→LF 标准化后的 UTF-8 内容校验，避免平台换行误报。先校验本地 Git HEAD 文件中的固定 commit，再校验文件指纹，因此同一 HEAD 下的本地修改也会被拒绝。工具不运行 Git 命令。
- 完整 MIT 原文复制到 `frontend/public/prompt-library/third-party/GPT-Image2-Skill-LICENSE.txt`，出处说明写入同目录 `NOTICE.txt`。作者/来源标记仍逐条保留在目录中。

## 封面与后续更新

导出不复制或请求上游约 442 MB 的预览图片，目录也不增加远程图片字段。页面使用本地风格示意封面；替换封面应由前端按稳定提示词 ID 配置本地图片映射，正文、作者和出处字段不应随封面改变。

更新来源版本时，需要明确审核新源版本，修改固定 commit 与对应的 33 文件指纹，并同步完整数量/字段测试和归属说明；不要为了通过导出而只修改来源 HEAD 或关闭指纹校验。中文分类词可以独立编辑，但修改后应重新导出并运行完整测试。现有来源解析器保持原样，本工具不改 Maven 模块或付费向量导入配置。
