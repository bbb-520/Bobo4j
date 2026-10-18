# README 视觉维护

根 `README.md` 是公开文档的唯一正文来源；之前的完整本地开发指南保存在根 `DEVELOPMENT.md`，不公开导出。发布工具仍兼容历史提交的独立 README 模板，当前不再保留重复模板。

配图位于 `assets/readme/`，只允许 `hero.svg`、`task-flow.svg`、`architecture.svg` 三个指定文件进入公开树。米白横线纸、黄色标记、手绘边框、红色波浪线和楷体中文沿用同一视觉体系。

在 Windows 执行 `./ops/readme/generate-assets.ps1` 可重新生成配图，需要系统的 KaiTi 与 Segoe Print 字体。文字转为 SVG 字形路径，GitHub 展示不依赖读者安装字体；标题、说明和正文替代文本保留图片语义。修改画面中的文字后同步检查 README 的替代文本与可读正文。

发布前运行发布工具 Python 测试、文档链接检查，检查 XML 和 HTML 图像引用，并在浏览器查看桌面与手机宽度。SVG 不使用外部字体、脚本或远程图片。说明中只写已实现能力：当前 5 个内置工具，MCP 接入属于后续规划。

本次为纯文档发布，在 `--prepare` 选择 README 和三张配图，确认差异中没有业务源码、POM 或数据库迁移后再推送；无需替换运行中的服务镜像。
