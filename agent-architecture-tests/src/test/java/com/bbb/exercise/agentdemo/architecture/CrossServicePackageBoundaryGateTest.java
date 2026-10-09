package com.bbb.exercise.agentdemo.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 设计文档 §15.2：「领域服务不得依赖其他领域服务」。
 *
 * <p>这是 Phase C（消除复制实现、确立单一 owner）能成立的前提：只要 A 服务还在编译期
 * 引用 B 服务的类，"消除重复"就永远只能靠复制粘贴而不是靠抽契约。
 *
 * <p><b>为什么用字节码而不是扫 import 语句</b>：import 只覆盖常见写法，
 * 写成全限定名内联（{@code com.bbb.exercise.agentdemo.chatservice.X x;}）
 * 或反射就漏掉了。这里是"服务之间零耦合"这种不允许有例外的规则，
 * 所以用 ArchUnit 看真实依赖图。
 *
 * <p><b>为什么逐个模块 {@code importPath} 而不是一次性 {@code importPackages}</b>：
 * 五个服务各自都带一份遗留根包 {@code com.bbb.exercise.agentdemo1_0} 的副本，
 * 一次性导入会出现同名类冲突。这里用 {@link ImportOption} 把遗留根包整体排除，
 * 于是每个模块只剩下自己独有的 {@code com.bbb.exercise.agentdemo.<service>} 包，
 * 再合并导入就没有任何 FQN 重叠。
 *
 * <p>遗留根包内部"新服务的类引用旧单体的类"不属于本条规则的范围，
 * 那部分存量耦合由 {@link RootPackageCouplingSnapshotGateTest} 以快照方式收敛。
 */
class CrossServicePackageBoundaryGateTest {

    /** 领域服务模块 → 该模块自己的领域包（{@code ..} 表示含全部子包）。 */
    private static final List<Service> SERVICES = List.of(
            new Service("agent-auth-service", "com.bbb.exercise.agentdemo.authservice"),
            new Service("agent-chat-service", "com.bbb.exercise.agentdemo.chatservice"),
            new Service("agent-media-service", "com.bbb.exercise.agentdemo.mediaservice"),
            new Service("agent-content-service", "com.bbb.exercise.agentdemo.contentservice"),
            new Service("agent-orchestrator-service", "com.bbb.exercise.agentdemo.orchestrator"),
            new Service("agent-rag-service", "com.bbb.exercise.agentdemo.ragservice"));

    /** 遗留根包的字节码位置标记；导入时整体排除，避免跨模块同名类冲突。 */
    private static final String LEGACY_ROOT_MARKER = "agentdemo1_0";

    @Test
    @DisplayName("领域服务之间不存在任何编译期依赖")
    void servicesMustNotDependOnEachOther() {
        JavaClasses classes = allServiceClasses();
        List<String> violations = new ArrayList<>();

        for (Service from : SERVICES) {
            for (Service to : SERVICES) {
                if (from.equals(to)) {
                    continue;
                }
                ArchRule rule = noClasses()
                        .that().resideInAPackage(from.packagePattern() + "..")
                        .should().dependOnClassesThat().resideInAPackage(to.packagePattern() + "..")
                        .because(from.module() + " 只能通过 HTTP / discovery 名调用 " + to.module()
                                + "，不能编译期引用它的类");
                try {
                    rule.check(classes);
                } catch (AssertionError failure) {
                    violations.add(failure.getMessage());
                }
            }
        }

        assertThat(violations)
                .as("领域服务之间出现了编译期依赖。要跨服务复用，先把类型沉到 agent-api（契约 DTO）"
                        + "或 agent-common（无业务归属的公共能力），不要在服务之间直接 import")
                .isEmpty();
    }

    @Test
    @DisplayName("门禁确实导入了 5 个服务的字节码（避免规则空跑）")
    void serviceClassesAreImported() {
        JavaClasses classes = allServiceClasses();
        for (Service service : SERVICES) {
            long imported = 0;
            for (JavaClass javaClass : classes) {
                if (javaClass.getPackageName().equals(service.packagePattern())
                        || javaClass.getPackageName().startsWith(service.packagePattern() + ".")) {
                    imported++;
                }
            }
            assertThat(imported)
                    .as("服务 %s 的领域包 %s 一个类都没导入到，规则等于空跑",
                            service.module(), service.packagePattern())
                    .isGreaterThan(0);
        }
        assertThat(classes.size())
                .as("导入到的类总数明显偏少，说明 module 的 target/classes 没被正确识别")
                .isGreaterThan(SERVICES.size());
    }

    /**
     * 五个服务模块的字节码合并导入，排除遗留根包。
     *
     * <p>不要改成直接读 Maven test classpath：Spring Boot 重打包后类在 {@code BOOT-INF/classes/}，
     * 而且五个模块各自一份遗留根包副本，结论会随构建阶段漂移。
     */
    private static JavaClasses allServiceClasses() {
        Path[] paths = SERVICES.stream()
                .map(service -> ModuleClasses.classesOf(service.module()))
                .toArray(Path[]::new);
        return new ClassFileImporter()
                .withImportOption(location -> !location.contains(LEGACY_ROOT_MARKER))
                .importPaths(paths);
    }

    private record Service(String module, String packagePattern) {
    }
}
