package com.bbb.exercise.agentdemo.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 快照门禁：新服务对遗留根包 {@code com.bbb.exercise.agentdemo1_0} 的耦合只允许变少。
 *
 * <p>这是 Phase B（关闭根包扫描）与 Phase C（消除复制实现）的输入清单。
 * 现在有 10 余处扫描声明和若干编译期 import，一次性全红不现实，
 * 因此按"不许新增"来守门：
 * <ul>
 *     <li>新写的服务如果又去 {@code scanBasePackages} 根包 → 变红；</li>
 *     <li>新写的类如果又 import 根包 → 变红；</li>
 *     <li>把旧的耦合消掉 → 只提示基线可收缩。</li>
 * </ul>
 * 注意"扫描声明"和"编译期 import"分开统计：前者是 Spring 运行时耦合，
 * 后者是编译期依赖，两者的清理动作和风险都不一样。
 */
class RootPackageCouplingSnapshotGateTest {

    private static final String SCAN_BASELINE = "root-package-scans.txt";
    private static final String IMPORT_BASELINE = "root-package-imports.txt";

    @Test
    @DisplayName("没有新增的根包扫描声明")
    void noNewRootPackageScanDeclaration() {
        assertNoNewEntries(
                ArchitectureState.rootPackageScanDeclarations(),
                SCAN_BASELINE,
                "新增了扫描 com.bbb.exercise.agentdemo1_0 的组件扫描声明");
    }

    @Test
    @DisplayName("没有新增的新包 → 根包 编译期依赖")
    void noNewRootPackageImport() {
        assertNoNewEntries(
                ArchitectureState.rootPackageImports(),
                IMPORT_BASELINE,
                "新风格包又 import 了遗留根包");
    }

    @Test
    @DisplayName("两个基线的条目格式与统计口径一致")
    void baselinesRepresentScanAndImportSeparately() {
        assertThat(Baseline.entries(SCAN_BASELINE))
                .as("扫描声明条目应带注解名，便于直接看出是哪种扫描")
                .allMatch(entry -> entry.contains("|@"));
        assertThat(Baseline.entries(IMPORT_BASELINE))
                .as("import 条目只到文件粒度")
                .allMatch(entry -> entry.startsWith("agent-") && entry.endsWith(".java"));
    }

    private static void assertNoNewEntries(List<String> current, String baselineName, String description) {
        Set<String> registered = new LinkedHashSet<>(Baseline.entries(baselineName));

        List<String> added = new ArrayList<>();
        for (String entry : current) {
            if (!registered.contains(entry)) {
                added.add(description + "：" + entry);
            }
        }

        assertThat(added)
                .as("新代码不得再耦合遗留根包；先把能力归到 agent-common / agent-api，"
                        + "再同步精简 %s", baselineName)
                .isEmpty();

        List<String> resolved = registered.stream().filter(entry -> !current.contains(entry)).toList();
        if (!resolved.isEmpty()) {
            System.out.println("基线可收缩：" + baselineName + " 已消除 " + resolved.size()
                    + " 条 → " + resolved);
        }
    }
}
