# platform-parent 模块帮助文档

## 1. 模块作用

本模块是所有 Java 子模块的 Maven 父工程，负责统一 Java 版本、依赖版本和构建默认行为。它的 `packaging` 为 `pom`，没有业务代码、HTTP 接口、数据库表或独立启动进程。

必须区分两个 POM：根目录的 [pom.xml](../pom.xml) 通过 `<modules>` 聚合 12 个模块；本模块的 [pom.xml](pom.xml) 通过父子继承管理构建规则。聚合决定“一次构建哪些模块”，继承决定“子模块使用哪些默认配置”，两者职责不同。

## 2. 版本管理如何实现

本模块继承 `org.springframework.boot:spring-boot-starter-parent:4.1.1`，并声明以下属性：

| 属性 | 当前值 | 用途 |
| --- | --- | --- |
| `java.version` | `21` | 统一 Java 编译基线，支持业务中的虚拟线程等能力 |
| `spring-ai.version` | `2.0.1` | Spring AI BOM |
| `spring-cloud.version` | `2025.1.0` | Spring Cloud BOM |
| `spring-cloud-alibaba.version` | `2025.1.0.0` | Spring Cloud Alibaba BOM |

三个 BOM 在 `dependencyManagement` 中以 `type=pom`、`scope=import` 导入。子模块声明受 BOM 管理的依赖时可以省略版本；**导入 BOM 不会自动把所有库加入子模块**，实际依赖仍由各子模块的 `<dependencies>` 决定。

部分依赖在子模块中显式指定版本，例如 Tika、Milvus SDK、MyBatis Plus、OSS SDK 和支付宝 SDK。这些版本不集中在本 POM 中，升级前需要同时检查使用方的 POM。

## 3. 子模块如何使用

子模块的 `<parent>` 指向 `com.bbb.exercise:platform-parent:0.0.1-SNAPSHOT`，相对路径为 `../platform-parent/pom.xml`。公共 Java 代码则分别放在 [agent-api](../agent-api/README.md)、[agent-common](../agent-common/README.md) 和 [agent-runtime](../agent-runtime/README.md)，不能把父 POM 当作代码依赖库。

服务模块自行声明 `spring-boot-maven-plugin`，负责生成可执行 JAR；普通库模块保持普通 JAR。父工程本身不承担 Spring Boot 的打包或启动职责。

## 4. 常用命令

以下命令均在仓库根目录执行，需要 JDK 21 和 Maven：

```powershell
# 验证父 POM 结构
mvn -pl platform-parent validate

# 查看一个实际服务继承后的最终配置
mvn -pl agent-chat-service help:effective-pom

# 查看依赖的最终版本及传递关系
mvn -pl agent-chat-service -am dependency:tree

# 验证整个 Maven reactor
mvn '-Dmaven.compiler.fork=true' verify
```

`-pl` 选择模块，`-am` 同时构建它依赖的 reactor 模块。修改父 POM 后只验证父模块不足以证明所有服务兼容，需要执行完整 reactor 验证。

## 5. 维护和排错

- **编译目标版本不支持**：检查 `java -version` 与 `mvn -version`，Maven 使用的 JDK 也必须符合 Java 21 基线。
- **依赖版本与预期不一致**：先查看 `help:effective-pom` 和 `dependency:tree`，排查显式版本、BOM 管理和传递依赖。
- **单独进入子目录后父 POM 无法解析**：检查相对路径和仓库结构；优先从根目录执行 reactor 构建。
- **升级框架**：同时核对 Spring Boot、Spring AI、Spring Cloud 和 Alibaba BOM，运行各服务的配置契约测试与架构门禁；不能仅凭依赖下载成功判断兼容。

相关验证见 agent-architecture-tests（本地路径 `../agent-architecture-tests/README.md`，不随核心源码发布） 和 agent-common 的配置契约测试目录（本地路径 `../agent-common/src/test/java/com/bbb/exercise/agentdemo/common/config`，不随核心源码发布）。本文版本描述以当前工作区 POM 为准。
