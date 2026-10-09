package com.bbb.exercise.agentdemo.architecture;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基线重建工具：把当前实际架构状态原样打印出来，永远通过。
 *
 * <p>它的价值有两个：
 * <ol>
 *     <li>基线文件格式的<b>唯一权威说明</b>——要重建基线，跑这个测试并把打印结果贴回
 *     {@code src/test/resources/architecture-baseline/}；</li>
 *     <li>门禁失败时的对照物——失败信息里说"多了这一条"，这里能直接看到"现在到底有哪些"。</li>
 * </ol>
 * 运行：{@code mvn -pl agent-architecture-tests -am test -Dtest=BaselineDumpTest}
 */
class BaselineDumpTest {

    @Test
    void dumpRootPackageCoupling() {
        dump("root-package-scans.txt（扫描声明：模块|路径|注解(限定/无参)）",
                ArchitectureState.rootPackageScanDeclarations());
        dump("root-package-imports.txt（新包 import 根包：模块|模块内路径）",
                ArchitectureState.rootPackageImports());
    }

    @Test
    void dumpDuplicates() {
        List<String> duplicates = ArchitectureState.duplicateMainSources();
        Map<String, Set<String>> owners = ArchitectureState.duplicateMainSourceOwners();
        System.out.println("=====BEGIN duplicate-classes.txt（跨模块重复主源码，共 " + duplicates.size() + "）=====");
        duplicates.forEach(fqn -> System.out.println(fqn + "|" + String.join(",", owners.getOrDefault(fqn, Set.of()))));
        System.out.println("=====END duplicate-classes.txt=====");
    }

    @Test
    void dumpApiContracts() {
        System.out.println("=====BEGIN api-contracts.txt（FQN|外部引用文件数|状态）=====");
        ArchitectureState.apiContractExternalReferences().forEach((fqn, references) -> System.out.println(
                fqn + "|" + references + "|" + (references == 0 ? "DEAD" : "REFERENCED")));
        System.out.println("=====END api-contracts.txt=====");
    }

    @Test
    void dumpMigrationsAndClaims() {
        dump("non-owner-migrations.txt（无执行入口的 SQL，已登记禁止静默删除）",
                ArchitectureState.nonOwnerMigrations());
        dump("claim 实现参考清单（只做提示，不落基线文件）",
                ArchitectureState.claimImplementationFiles());
    }

    @Test
    void dumpModuleOwnership() {
        dump("common-module-boundary.txt（agent-common 归属越界：模块内路径|类别）",
                ArchitectureState.commonModuleBoundaryViolations());
        dump("Controller↔持久化 违规（立即变红，无基线；为空才是正常）",
                ArchitectureState.controllersDependingOnPersistence());
    }

    private static void dump(String title, List<String> lines) {
        System.out.println("=====BEGIN " + title + "=====");
        lines.forEach(System.out::println);
        System.out.println("=====END=====");
    }
}
