# MCP 接入与维护

## 独立库模块

客户端生产代码、Schema 适配、公开工具策略、结果处理及四个审核定义位于 `agent-mcp`（包 com.bbb.exercise.agentdemo.mcp），作为普通 JAR 嵌入编排服务。模块只依赖 agent-api 和客户端基础库，不依赖业务服务或数据库。使用底层 WebFlux 传输和明确的 SDK/Jackson3 提供器，不引入自动工具回调 Starter；独立库明确声明 Reactor Netty 客户端依赖。

编排服务显式导入 McpConfiguration，通过 McpToolSourceAdapter 把 McpToolDescriptor 转成执行计划，实际 IO 仍由 StepRunner 登记账本后派发。McpDispatchUncertain 属于编排执行包。参数、固定指纹、属性名、租户范围和回执格式均保持兼容；没有新进程、端口或迁移。通用协议测试与账本测试保留在编排模块，客户端单元测试归 agent-mcp，架构检查禁止反向依赖。

发布前除既有验收外，还检查可执行 JAR 中恰有一个普通 agent-mcp 库及审核资源，且没有旧编排 MCP 实现/Schema 副本；真实 Java 验收会输出 MCP_MODULE_SOURCE，证明从该库加载客户端。历史发布记录以对应旧提交中的路径为准。

## 第二阶段：公开文档预设

首批预设为 Microsoft Learn 搜索/读取，以及 DeepWiki 仓库目录/问答，共四个只读工具，无新增 API Key。官网说明见 README 的来源链接。DeepWiki 当前协议工具名为 `ask_wiki_question`，不能使用文档旧示例中的 `ask_question`。预设不会开放 `read_wiki_contents`、代码样例搜索等目录中的其他工具。

源码默认 `MCP_ENABLED=false` 和 `MCP_PUBLIC_DOCS_ENABLED=false`。生产启用需同时指定二者为 true、`MCP_REQUEST_TIMEOUT=30s`，以及 `MCP_PUBLIC_DOCS_TENANTS=精确租户ID`（多个租户用逗号分隔）。不能为空或 `*`；与自定义 `mslearn`/`deepwiki` 连接冲突时启动失败。面向该租户的 AGENT 模式生效，CHAT/DOCUMENT_QA 保持原路径。

固定指纹来自 `src/main/resources/mcp/public-docs/*.input.json` 的审核源码，不根据实时发现自动更新。远端定义漂移后对应工具不放行，应重新获取定义、核对只读及收费策略、更新资源和测试再发布。模型看到的是远端 Schema 与本地限制的交集；搜索/问题必填、最多 2,000 字符，仓库限定单个 owner/repo，读取限定 HTTPS learn.microsoft.com 文档及受限 view/tabs 参数。参数在登记步骤前校验，派发端再次校验。

完整输出 Schema 先按最多 64,000 字符离线验证，再做有损投影；请求参数仍最多 8,000 字符。Microsoft 搜索保留最多四条标题、1,500 字符摘要及来源 URL；文档读取和 DeepWiki 文本最多 12,000 字符，并附请求来源页标识。`truncated=true` 表示确有省略，不代表读过剩余内容。泛用 MCP 工具保持原有过大结果拒绝规则；错误、空结果、非法输出不转换成成功。响应解码上限仍 64,000 字节，超限会保留未知派发责任，不自动重发。

运行指标 `agent.mcp.approved.tools{server=mslearn|deepwiki}` 各应为 2；该指标仅通过内网 Actuator 观察，不新增公开未认证接口。目录不可用或 Schema 不匹配时降为 0，普通聊天仍可用。

本地 opt-in 真实测试：Maven 运行 `McpPublicDocumentationLiveTest` 并设 `-Dmcp.live=true`。该测试发出固定公开技术查询，通过真实 Java SDK 和独立 H2 账本验证四个回执及重放；不使用生产业务库、用户资料或模型计费。生产发布前由 `live_public_docs.py` 使用同一待部署 JAR 在隔离容器中运行该测试，之后才进入维护切换。私有 `deploy_public_docs.py` 复用第一阶段守护，必须使用新的发布目录，清单指定 mcpTenants，核对迁移不变，并保留旧镜像/config。关闭 `MCP_PUBLIC_DOCS_ENABLED` 或回滚编排镜像可撤回预设，未知回执不因关闭丢弃。

## 通用底座与第一阶段

MCP 客户端位于编排服务内，使用 Spring AI 2.0.1 的 WebFlux Streamable HTTP 传输和 MCP Java SDK。第一阶段只建立受控调用底座，不接入付费搜索、浏览器、文件写入或生产数据库工具。

## 默认行为

- `app.mcp.enabled=false`，没有默认外部连接。普通 CHAT/DOCUMENT_QA 流程不依赖 MCP 可用性。
- Spring MCP 自动客户端和自动 ToolCallback 注册保持关闭；本项目自己管理异步客户端并通过执行账本调用。
- 初始化、ping/工具目录检查及重连均在后台完成。请求超时默认 10 秒，可设 100ms–30s；断线后连接重试退避 5–60 秒，健康连接约每 30 秒检查一次。连接重试不重发工具调用。
- 不开放 sampling、roots、elicitation 或任意服务器地址输入。连接、租户和工具由运维配置。
- 启用连接前，应审核工具行为，并明确声明只读且无外部收费；不能用远端 annotations 代替审核。第一阶段不能通过声明绕过实际外部收费。
- 首期面向公开或租户共享数据，不接入用户私人账户数据；按用户的 OAuth 授权和凭据委托在后续阶段实现。

## 配置示例

在私有配置中心提供以下结构。该示例只适用于与测试夹具相同的 echo 工具；不要把示例租户或 HTTP 许可直接用于生产。

```yaml
app:
  mcp:
    enabled: true
    request-timeout: 10s
    connections:
      test:
        url: http://127.0.0.1:18090
        endpoint: /mcp
        allow-insecure-http: true
        bearer-token: ${MCP_TEST_TOKEN:}
        tenants: [demo]
        tools:
          echo:
            alias: mcp.test.echo
            description: 读取测试证据
            schema-sha256: 9c2fe9762111c1e93a19017a10e9403fb537083419ba45c46312aafe8b95aaab
            read-only: true
            no-external-charge: true
```

相应输入 Schema：

```json
{"type":"object","properties":{"query":{"type":"string","minLength":1}},"required":["query"],"additionalProperties":false}
```

Schema 指纹由 `ToolSchema.fingerprint()` 生成：先解析并按对象键排序序列化，再计算 SHA-256；不是对带空格的原始文本直接求摘要。正式接入其他工具时先独立取得并审核 `tools/list` 定义，再用该类计算指纹，填写工具别名、运维编写的说明和精确租户列表。远端定义变化后不会自动放行，需要重新审核和更新配置。第一阶段配置更新需要重启编排服务，不提供公开配置管理 API。

输入为 draft 2020-12 的对象 Schema，支持嵌套对象、数组、必填字段、枚举、范围和显式 null 等约束；参数正文最多 8,000 字符。禁止外部 `$ref`/`$dynamicRef` 和 `$id`，不访问远程 Schema；顶层身份/系统字段不由模型传入。已声明的输出 Schema 在本平台校验，SDK 也安装离线引用限制，且不开启会输出原始结果告警的 SDK schema caching。

工具发现逐页进行，最多 8 页、128 个工具、累计 64,000 字符定义；重复游标、重复工具名和超限目录会使该连接暂时不可用，不影响内置工具。已完成的模型决策从账本重放，目录变化不重建旧请求，也不绕过当前派发权限检查。

## 调用与恢复

经过租户筛选的工具说明进入 Agent 决策上下文，注册表再次检查完整参数 Schema，MCP 派发元数据固定连接 ID、远端工具名和 Schema 指纹。预算准入后先登记步骤，再发送请求。

成功回执保存到现有 `agent_execution_step`，相同步骤和参数重放直接读库。服务器返回的错误结果以 `isError=true` 保存，可供模型调整下一步，但不计作有效进展。归一化只保存文本、受限结构化内容与来源标识；总正文超过 16,000 字符返回 `MCP_RESULT_TOO_LARGE`。传输解码上限为 64,000 字节；凭据不写入步骤请求，配置的 Bearer 值在结果中脱敏。

MCP 结果不是模型计费回执，远端声明的 Token 不导入模型账本。模型决策和总结仍按已有模型链路结算。工具调用次数和执行时长仍进入任务预算。

超时、断线、进程崩溃或停止后的未确认调用保留 DISPATCHED 步骤，任务进入 WAITING_FOR_RECONCILIATION；不拿 MCP 请求号当作业务幂等保证，不查询模型计费服务，不自动重发。第一阶段不提供该类未知调用的自动恢复接口；运维确认实际结果后，用户可显式新建任务。取消本地等待不保证远端已撤销操作。

## 观察与回归

连接状态由 `McpConnectionManager.status()` 提供，包含 DISABLED、DISCONNECTED、READY、UNAVAILABLE、CLOSED；本阶段不新增公开状态 API，不把 MCP 可用性设为应用就绪门槛。

指标：`agent.mcp.connection.failures`、`agent.mcp.schema.rejections`，以及既有 `agent.tool.calls` 的 success/tool_error/unknown 结果。Token、连接凭据、远端异常详情不写入自定义错误消息。

测试包括：真实本地 Streamable HTTP 协议、完整 Agent→MCP→回答、五个内置工具、租户越权、定义变化、嵌套参数、错误/过大结果、账本重放、预算耗尽、停止、未知恢复、连接恢复不重发，以及 MCP 不可达时普通聊天完成。

回滚可关闭 `MCP_ENABLED` 并重启编排服务；已派发未知步骤仍保留，不因关闭功能丢弃回执责任。发布没有数据库迁移，只部署经独立公开快照验证的编排服务，默认不开启任何 MCP 连接。
