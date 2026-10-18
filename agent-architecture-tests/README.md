# agent-architecture-tests 模块帮助文档

## 1. 模块作用

这是仓库的架构门禁模块，用 JUnit、源码扫描和 ArchUnit 字节码规则防止服务拆分后的边界退化。它没有业务主源码、运行端口和数据库，不参与应用部署。

业务测试回答“某个功能是否正确”，本模块回答“实现是否仍遵守模块归属、唯一状态 owner、服务间边界和部署口径”。它不能替代真实数据库并发测试、模型质量评测或完整端到端测试。

## 2. 实现结构

| 代码 | 作用 |
| --- | --- |
| [Repository](src/test/java/com/bbb/exercise/agentdemo/architecture/Repository.java) | 定位仓库根、枚举模块源码、统一路径读取 |
| [ArchitectureState](src/test/java/com/bbb/exercise/agentdemo/architecture/ArchitectureState.java) | 由源码计算扫描、import、重复类、契约引用、迁移归属和 claim 清单 |
| [ModuleClasses](src/test/java/com/bbb/exercise/agentdemo/architecture/ModuleClasses.java) | 定位各模块 target/classes，支持隔离加载 |
| [Baseline](src/test/java/com/bbb/exercise/agentdemo/architecture/Baseline.java) | 读取已登记存量问题，过滤注释并拆分字段 |
| [BaselineDumpTest](src/test/java/com/bbb/exercise/agentdemo/architecture/BaselineDumpTest.java) | 打印当前实际状态，帮助诊断与维护基线 |
| [architecture-baseline](src/test/resources/architecture-baseline) | 存量登记文件 |

源码检查能看到注解中的字符串扫描范围，字节码检查能看到实际类依赖；两者互补。测试按模块输出目录导入字节码，避免 Spring Boot fat JAR 的 `BOOT-INF/classes` 和跨模块同名类影响调查范围。

## 3. 主要门禁检查什么

| 测试类 | 约束 |
| --- | --- |
| `HardCutoverContractTest` | 当前服务切换后的目标边界与遗留实现约束 |
| `LayerBoundaryArchTest` | Gateway / Orchestrator 不依赖遗留根包，Gateway 不编译期依赖业务实现 |
| `CrossServicePackageBoundaryGateTest` | 被检查的领域服务之间不编译期耦合实现包 |
| `ControllerPersistenceBoundaryGateTest` | Web 层不直接依赖 JDBC / Mapper 等持久化能力 |
| `CommonModuleBoundarySnapshotGateTest` | Common 不加入 Web、持久化及包外实现，存量边界只允许收缩 |
| `RootPackageCouplingSnapshotGateTest` | 遗留根包扫描和 import 不增加 |
| `DuplicateClassSnapshotGateTest` | 不新增遗留重复类或扩大副本持有模块 |
| `DeadApiContractSnapshotGateTest` | API 契约登记与零外部引用状态同步 |
| `MigrationOwnershipGateTest` | 表只能由 owner 模块创建，登记无执行入口 SQL |
| `SingleClaimImplementationGateTest` | 图片、Agent 执行、RAG 作业各保留唯一领取实现 |
| `ExecutionOwnershipGateTest` | 持久执行浏览器操作携带用户 / 租户身份，Worker 写操作携带租约 |
| `RetiredWorkflowContractTest` | REVISE 实现、路由和权限规则已移除，持久执行及历史迁移保留 |
| `GatewayAuthorizationBoundaryGateTest` | 清除客户端身份头，路由目标对应真实模块 |
| `DeploymentDescriptorDriftGateTest` | README 端口表、拓扑、模块 YAML 和 Nacos 导入配置一致 |

完整实现见 [测试目录](src/test/java/com/bbb/exercise/agentdemo/architecture)。规则的具体扫描范围以代码为准，测试类名不代表覆盖了所有未来模块或所有写入路径。

唯一 claim 当前归属：Media 的 `ImageJobService`、Orchestrator 的 `ExecutionStore`、RAG 的 `RagRepository`。这不是限制全系统只能有一个 Worker，而是要求每个领域保留一套权威领取算法。

## 4. 硬规则与基线

硬规则要求立即满足，不允许用基线豁免。快照规则登记已有问题，主要阻止新增，允许收缩。部分基线还要求登记文件继续存在，避免历史登记变成无法解释的条目；应阅读对应测试的断言。

当前基线包括：

- `root-package-scans.txt` / `root-package-imports.txt`：遗留包耦合。
- `duplicate-classes.txt`：重复类及持有模块。
- `api-contracts.txt`：契约、外部引用数和状态。
- `non-owner-migrations.txt`：无 owner 执行入口的迁移登记。
- `common-module-boundary.txt`：Common 的存量边界登记。

`BaselineDumpTest` 只打印事实，本身不会证明这些事实符合目标架构，也不会自动改写基线。修复失败时先判断是实现退化、规则需适应合法架构变更，还是登记需要收缩，不能把当前输出全部复制回基线以消除错误。

## 5. 如何运行

在仓库根目录，使用 JDK 21：

```powershell
# 构建所需模块并执行架构及上游测试
mvn -pl agent-architecture-tests -am '-Dmaven.compiler.fork=true' test

# 只选一个架构测试；上游无同名测试时允许继续
mvn -pl agent-architecture-tests -am '-Dmaven.compiler.fork=true' '-Dtest=DeploymentDescriptorDriftGateTest' '-Dsurefire.failIfNoSpecifiedTests=false' test

# 打印架构状态，不自动更新基线
mvn -pl agent-architecture-tests -am '-Dmaven.compiler.fork=true' '-Dtest=BaselineDumpTest' '-Dsurefire.failIfNoSpecifiedTests=false' test

# 完整验证
mvn '-Dmaven.compiler.fork=true' verify
```

`-am` 很关键：POM 用 test scope 依赖业务服务，使 reactor 先生成其 target/classes。直接在门禁模块中运行且缺少上游编译产物时，字节码检查会失败。

## 6. 防止门禁空跑

[archunit.properties](src/main/resources/archunit.properties) 设置 `archRule.failOnEmptyShould=true`，规则选不到类时失败，不能把空选择当作通过。

[pom.xml](pom.xml) 显式设置 Surefire 的 classesDirectory，并保留主资源锚点，避免没有主 Java 源码时测试被静默跳过。测试中还对导入类数量或扫描 Web 类数量做断言。

## 7. 排错和维护

- **target/classes 不存在**：从根目录加 `-am` 构建，不依赖可执行 JAR 的普通 classpath。
- **规则没有匹配任何类**：检查包名 / 选择器 / 编译输出，不关闭 failOnEmptyShould。
- **端口 / 服务名漂移**：同时核对根 README、模块 YAML、Nacos 导入、Compose 和路由配置。
- **新增 API 契约未登记**：确认真实消费方与边界后，按既定格式维护契约登记。
- **claim 清单变化**：先确认是否引入第二套算法或新增有独立 owner 的任务，再调整规则。
- **新增模块**：更新根聚合、Repository 模块范围、所需 test 依赖、字节码扫描及部署约束，避免模块未被检查。

本文介绍门禁设计和命令，不代表当前工作区全部业务测试已经通过；运行结果以新执行的 Surefire 报告为准。
