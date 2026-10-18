# 完整开发基线与公开发布

本项目在同一 Git 仓库维护两种版本视图：

- `codex/development`：完整的本地开发分支，保存源码、测试、构建定义、运维脚本及项目文档，不推送到公开 GitHub。
- `main` / `origin/main`：公开核心源码，由发布工具生成，只有业务 `src/main`、必要 Maven POM、根 README、三张固定路径的 README SVG 配图、`.gitignore` 和 `.gitattributes`。

公开提交以此前公开 main 为唯一父提交，开发分支只以提交号记录，不作为公开提交的祖先。这样不会通过 Git 历史意外带出本地测试和运维资料。

## 开发与验证

在开发分支修改和提交。真实 `.env`、`secrets/`、下载包、构建产物及临时日志保持忽略；不要使用 `git add -f` 添加它们。新建业务文件必须进入开发提交，发布工具会拒绝未提交的工作区。

```sh
git switch codex/development
./mvnw -B -ntp -Dmaven.compiler.fork=true verify
python -m unittest discover -s ops/release -p 'test_*.py'
python scripts/check-doc-links.py
```

Windows 使用 `mvnw.cmd`。若测试环境不能动态附加 Mockito agent，使用已安装 Mockito JAR 的 `-javaagent` 参数，不跳过相关测试。Docker/MySQL/Milvus 环境缺失的测试跳过项必须单独记录；健康检查不能替代业务验收。

## 安装发布保护

使用 Python 3.10 或更新版本运行：

```sh
python ops/release/public_source.py --install-guard
```

安装到本仓库的 pre-push hook 只允许向公开仓库推送符合公开文件规则的 main，阻止开发分支、私有标签、新增私有历史和凭据文件。已有用户 hook 不会被静默覆盖。保护仅作用于本地正常 Git 推送；恢复仓库或更换机器后重新安装。

## 可重复公开导出

确认完整开发版本测试通过并提交后：

```sh
python ops/release/public_source.py --prepare --record tmp/publication.json
git archive --format=tar --output=tmp/public-source.tar <publication.json中的publicCommit>
# 将公开快照解到临时目录，独立执行 Maven package，确认它不依赖本地私有文件。
python ops/release/public_source.py --push tmp/publication.json
```

`--prepare` 不修改工作区和当前开发分支。它去除聚合 POM 中的架构测试模块，使用根 README（历史版本也支持独立公开模板），记录开发提交、公开提交、公开树及旧公开父提交。`--push` 核对工作区、开发提交及远端旧版本，拒绝源代码变化或远端并发更新，不进行强制推送。

开发分支含有尚未发布的功能时，使用重复的 `--path <核心文件路径>` 只覆盖此前公开版本中的指定文件。记录中的 `selectedPaths` 明确发布范围。必须独立构建这个公开快照并在该快照运行相关测试，不能用完整开发分支的测试结果替代；数据库迁移也以实际发布快照为准。纯 README 与配图发布只需检查链接、图片和发布文件范围，无需重建未改变的业务产物。

## 服务器发布与恢复

模块移动时可在精选发布中重复传入 `--remove <旧核心路径>`。删除项必须属于公开白名单、存在于公开父提交且已从当前开发源码移除，同时必须显式提供 `--path` 发布范围。`removedPaths` 写入版本记录；未指定的历史公开文件继续保留，不能借删除放开私有文件或历史保护。删除后的公开快照仍需独立构建验证。

每次发布在服务器私有目录保存：开发/公开提交号、每个服务的 JAR SHA-256、当前及前一镜像 ID、配置快照、数据库迁移对比、测试统计、业务验收结果和回滚状态。完整开发 Git bundle 保存在本地 `.git/backups/`；本会话用户已授权同时备份到指定服务器，文件限制 root 读取并核对摘要。真实环境和数据库备份保持 root 私有权限，不放入 Git bundle。

部署前比较已运行 JAR 与新 JAR 的全部 SQL/Java 迁移及 Flyway 历史。现有迁移变化必须先查明原因；不得删除历史、修改 checksum 或执行自动 repair 来绕过检查。先停止新请求并等待进行中任务结束，再逐个服务替换和验证；失败时恢复旧镜像与配置并确认健康。

恢复开发仓库使用私有 bundle：`git clone -b codex/development <development.bundle> <directory>`。从最后一次发布记录确认公开远端 URL，再安装 pre-push guard。不要直接把开发分支推送到公开仓库。

## 文档问答回归

每次修改模型或问答提示词后验证：原始“分析该简历”问题、长文档、表格中的数字/单位、缺乏证据的问题、同一文档会话连续追问、非法引用、无引用事实、模型超时及幂等重放。不能通过改写用户问题强制引用格式或放松事实校验来声明验收成功。
