package com.bbb.exercise.agentdemo.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 字节码级边界门禁（ArchUnit）。
 *
 * <p>为什么不能只靠文本扫描：源码里 {@code scanBasePackages = "..."} 只是一个字符串，
 * "有没有真的依赖根包的类"只有看字节码才准确；反过来，字符串形式的包扫描
 * 字节码里也看不出来，所以两种手段互补，都要有。
 *
 * <p>为什么不直接 {@code importPackages(...)}：根包 {@code com.bbb.exercise.agentdemo1_0}
 * 在多个模块里各有一份同名类，全部放到同一个 classpath 上会出现"同名类谁生效"的歧义，
 * ArchUnit 也会报类冲突。这里改为<b>按模块输出目录逐个导入字节码</b>，
 * 每个模块的调查范围都是确定的。
 *
 * <p>当前只有 {@code agent-gateway} 与 {@code agent-orchestrator-service} 做到了"新包零依赖根包"，
 * 所以这两条是硬门禁；其余模块存量耦合走 {@link RootPackageCouplingSnapshotGateTest} 的快照。
 */
class LayerBoundaryArchTest {

    private static final String MIGRATED_ROOT = "com.bbb.exercise.agentdemo1_0..";

    @Test
    @DisplayName("Gateway 不得依赖遗留根包")
    void gatewayMustNotDependOnMigratedRootPackage() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.bbb.exercise.agentdemo.gateway..")
                .should().dependOnClassesThat().resideInAPackage(MIGRATED_ROOT)
                .because("Gateway 只做路由与不信任输入，不应耦合旧单体实现");

        rule.check(importModule("agent-gateway"));
    }

    @Test
    @DisplayName("Orchestrator 不得依赖遗留根包")
    void orchestratorMustNotDependOnMigratedRootPackage() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.bbb.exercise.agentdemo.orchestrator..")
                .should().dependOnClassesThat().resideInAPackage(MIGRATED_ROOT)
                .because("AgentRun 状态机必须只依赖自己的领域模型");

        rule.check(importModule("agent-orchestrator-service"));
    }

    @Test
    @DisplayName("Gateway 不得直接依赖任何业务服务的实现类")
    void gatewayMustNotDependOnServiceImplementations() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.bbb.exercise.agentdemo.gateway..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.bbb.exercise.agentdemo.authservice..",
                        "com.bbb.exercise.agentdemo.chatservice..",
                        "com.bbb.exercise.agentdemo.mediaservice..",
                        "com.bbb.exercise.agentdemo.contentservice..",
                        "com.bbb.exercise.agentdemo.orchestrator..")
                .because("Gateway 只能通过 HTTP/discovery 名调用下游，不能编译期依赖服务实现");

        rule.check(importModule("agent-gateway"));
    }

    @Test
    @DisplayName("门禁确实导入到了字节码（避免规则空跑）")
    void importedModulesAreNotEmpty() {
        assertThat(importModule("agent-gateway").size()).isGreaterThan(0);
        assertThat(importModule("agent-orchestrator-service").size()).isGreaterThan(0);
    }

    private static JavaClasses importModule(String module) {
        return new ClassFileImporter().importPath(ModuleClasses.classesOf(module));
    }
}
