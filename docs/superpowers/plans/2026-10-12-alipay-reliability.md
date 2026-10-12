# 支付宝支付稳定性修复计划

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 移除微信支付，修复退款无出口、二维码恢复和支付对账，提交前后端及 README 到用户指定 GitHub 仓库。

**Architecture:** 延续现有 Java/Spring/JDBC 事务、数据库租约和支付宝 SDK；仅增加 Flyway 迁移，不修改旧迁移或删除历史订单。前端沿用 React 充值面板。实施者选择：退款最多自动核对 12 次或 24 小时，之后转 REVIEW，资金保持扣留；人工必须记录操作者、原因与外部证据编号，才能确认终态。正常成功仍优先使用已验签的渠道证据。

**Tech Stack:** Java 21, Spring Boot, JDBC/Flyway, Alipay Java SDK, React/TypeScript, H2 and signed local HTTP fixtures.

**Scope:** 用户本次请求即行为要求；提交 GitHub 和更新 README，不在本轮自动修改线上服务或实际执行金融交易。

## Constraints and review focus

- 删除微信新支付和公开回调、配置、UI；不删除历史微信订单。
- 超时、不明确拒绝和 SDK 验签失败均不能制造退款成功/失败或恢复可消费余额。
- 二维码刷新复用订单和金额，数据库租约失败显式返回错误；失败保留已有有效二维码。
- 记录渠道金额、状态、核对时间和失败；区分实际收到的字段与缺失字段，不把查询成功等同资金核对成功。
- 支付资金流水在钱包事务内追加；重复回调和重复人工处理不得重复加减余额。
- 人工处理与账实查询需服务端 RBAC，未核对的历史记录不得标记为对账通过。

## Tasks

- [x] RED：用现有 API 复现过期二维码静默保存、微信可下单、退款超过期限仍 PENDING、退款查询字段不符仍派发、实收字段错误仍入账；前端复现二维码绘制失败无法重试。
- [x] 退款：新增核对次数/期限、REVIEW 和审计人工出口，终态资金操作与流水原子化；租约过期和重复操作测试。
- [x] 二维码：增加同订单刷新接口、版本与错误信息，前端重新获取和重新绘制；所有权、过期、并发、刷新恢复测试。
- [x] 对账：扩展已验签字段校验与持久化记录、资金流水、管理员差异查询；覆盖金额/商户/币种/退款号/交易号不符、缺失字段和幂等。
- [x] 移除微信实现、浏览器入口、公开回调白名单和配置，保留历史可读数据；相关安全测试。
- [x] 验证：后端受影响模块回归、前端全部脚本与构建、检查迁移、敏感信息及 diff；更新两个 README/配置说明，正常推送 main 并核对远程 SHA。

## 验证记录

- 七个应用模块及依赖的 Maven package 回归通过；排除了基线中因缺失示例部署文件而失败的 `DeploymentAssetsContractTest#deploymentAssetsRemainPresentAtRepositoryRoot`。
- 最终 auth/gateway 及依赖回归通过；审查修复后，支付流程、网关、账务、V9 历史迁移测试再次通过。
- 前端全部 13 个测试脚本及生产构建通过。
- 独立审查的两项 Important 问题（MySQL 比较表达式类型、退款完成后旧查询覆盖）已修复并复审。
- 本轮未执行真实扣款/退款或生产部署。
