# 07：统一最终提示词组装

日期：2026-10-18，Asia/Shanghai。对应[优化任务书 T4](codex-optimization-prompt.md)。本轮仅本地验证与源码交付，不部署服务器、不调用真实收费模型。

## 改动与原因

原先公共规则在 `ModelPrompts`，执行器另有聊天、决策和图片证据规则；预算与供应商发送分别拼接 system。现在由 `ModelPrompts.taskRules` 管理静态任务规则，`forMode` 加入主备身份与公共护栏，`assemble` 统一生成最终 system。预算估算与供应商发送都调用同一入口，null 补充视为空，其他空格、换行和正文保持原样。

纯文本 CHAT 不再重复附加静态规则；带图 CHAT 会进入 VISION，因此保留共享 CHAT 任务规则作为补充。DECISION 保留完整外循环规则，调用方继续附加按当前 scope 生成的工具名称与参数 schema，实际工具执行仍由服务端校验授权。文档、摘要、评估和分类模式的原规则保留。

`IMAGE_EVIDENCE` 是共享规则选择器，没有新增内部请求模式或计费能力。图片检查点继续通过内部 CHAT 请求传递附件、使用 VISION 模型，并保留原提取规则字符串；原检查点的请求 hash 和资产引用保持兼容。

独立审查发现旧版未完成步骤在账务查询返回 404 或已明确失败后，重建新提示词会与旧步骤 hash 冲突。现在先复用按归属、执行任务和步骤键绑定的完整原请求，校验目标、路径、调用号与模式，再交给原 Store 检查参数 hash。恢复时不改写旧 system、用户输入、会话或附件，不更换调用号；已完成回执继续直接重放。

## 验证

新增测试先确认组装入口缺失；带图 CHAT 的补充规则、旧待处理步骤恢复也分别经历失败复现后修复。定向测试覆盖共享任务约束、空与非空补充、旧图片检查点、动态工具范围及旧步骤恢复。

供应商传输测试通过本地 HTTP 捕获实际 JSON 请求，覆盖 GPT/Qwen、七种现有模式、主备与三类补充共 84 种组合。按实际 system/user 的 UTF-8 字节、协议与输出预留及图片附加量独立计算完整预算，同时检查角色、图片块、输出上限和真实模拟回执字段。另覆盖旧文本 CHAT、带图 CHAT、DECISION 与 404/明确失败的六种恢复组合，核对完整原请求、hash 和派发次数。

最终定向回归 21 项全部通过；完整 `clean verify` 的 14 个 reactor 项目均成功，共 767 项测试：743 项通过、24 项按环境门控跳过，失败和错误均为 0，其中架构门禁 56 项全部通过。定向用例包含在完整回归中，不重复累计。公开交付源码的本地文档链接检查无失效目标，独立复核确认旧请求兼容问题已解决，没有剩余重要发现。

上述测试证明程序组装与恢复行为，不代表真实模型的指令遵循、回答质量、延迟或成本已经测量；这些仍属 09、13。预算公式、结算规则、数据库结构与对外事件契约没有在 07 调整，前端源码也未修改。后续任务仍按原编号推进。

## 代码导航

- [共享规则与最终组装](../agent-api/src/main/java/com/bbb/exercise/agentdemo/api/model/ModelPrompts.java)
- [完整调用预算](../agent-api/src/main/java/com/bbb/exercise/agentdemo/api/model/TokenBudgetEstimator.java)
- [供应商传输与请求捕获测试](../agent-runtime/src/test/java/com/bbb/exercise/agentdemo/runtime/client/ModelProviderTransportTest.java)
- [执行器提示词与恢复回归](../agent-orchestrator-service/src/test/java/com/bbb/exercise/agentdemo/orchestrator/execution/ExecutionPromptTest.java)
