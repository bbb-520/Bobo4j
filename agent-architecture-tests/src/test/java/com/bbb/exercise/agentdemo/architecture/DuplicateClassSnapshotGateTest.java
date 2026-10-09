package com.bbb.exercise.agentdemo.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 快照门禁：跨模块重复的主源码只允许变少，不允许变多。
 *
 * <p>当前存量 65 个重复全限定名（Phase 0 实测 66，Phase B 第 1 批迁走
 * {@code config/WebConfig} 后收缩到 65），一次性全红只会让人绕过门禁，
 * 所以这里采取"基线快照"策略：
 * <ul>
 *     <li>出现全新的重复 FQN → 变红；</li>
 *     <li>已有的重复 FQN 又多了一份副本（比如 media 的 ImageJobService 又被复制到 content）→ 也变红；</li>
 *     <li>重复类被消除 → 什么都不做，只提示基线可以收缩（改好了不该被罚）。</li>
 * </ul>
 *
 * <p>第二条是关键：只比对 FQN 集合会漏掉"往已有重复里再塞一份"这种最常见的退化方式。
 * 因此基线第二列记录了持有模块集合，门禁要求"当前持有者必须是基线持有者的子集"。
 */
class DuplicateClassSnapshotGateTest {

    private static final String BASELINE = "duplicate-classes.txt";

    @Test
    @DisplayName("没有新增的重复类，也没有给已有重复类再加副本")
    void noDuplicateClassOutsideBaseline() {
        Map<String, Set<String>> registered = baselineOwners();
        Map<String, Set<String>> current = ArchitectureState.duplicateMainSourceOwners();

        List<String> violations = new ArrayList<>();
        current.forEach((fqn, owners) -> {
            Set<String> allowed = registered.get(fqn);
            if (allowed == null) {
                violations.add("全新重复类 " + fqn + "：" + owners);
                return;
            }
            Set<String> extra = new LinkedHashSet<>(owners);
            extra.removeAll(allowed);
            if (!extra.isEmpty()) {
                violations.add(fqn + " 又多了一份副本，新增持有者 " + extra
                        + "（基线允许的持有者是 " + allowed + "）");
            }
        });

        assertThat(violations)
                .as("重复类只能消除、不能增加；确需暂时保留时，必须在同一提交里说明归属方案并更新 %s", BASELINE)
                .isEmpty();
    }

    @Test
    @DisplayName("基线中已被消除或已收缩的条目只提示，不判失败")
    void resolvedDuplicatesOnlyReportShrinkage() {
        Map<String, Set<String>> registered = baselineOwners();
        Map<String, Set<String>> current = ArchitectureState.duplicateMainSourceOwners();

        List<String> fullyResolved = registered.keySet().stream().filter(fqn -> !current.containsKey(fqn)).toList();
        List<String> shrunk = new ArrayList<>();
        current.forEach((fqn, owners) -> {
            Set<String> allowed = registered.get(fqn);
            if (allowed != null && !allowed.equals(owners)) {
                shrunk.add(fqn);
            }
        });

        if (!fullyResolved.isEmpty() || !shrunk.isEmpty()) {
            System.out.println("基线可收缩：完全消除 " + fullyResolved.size() + " 个、持有者减少 " + shrunk.size()
                    + " 个 → " + fullyResolved + shrunk);
        }
    }

    @Test
    @DisplayName("基线本身是可解析的（路径 + 持有模块集合）")
    void baselineIsWellFormed() {
        List<String> malformed = Baseline.entries(BASELINE).stream()
                .filter(entry -> !Baseline.column(entry, 0).startsWith("com/bbb/exercise/")
                        || !Baseline.column(entry, 0).endsWith(".java")
                        || Baseline.column(entry, 1).isBlank())
                .toList();

        assertThat(malformed)
                .as("基线格式应为 <src/main/java 下相对路径>|<持有模块,逗号分隔>")
                .isEmpty();
    }

    private static Map<String, Set<String>> baselineOwners() {
        Map<String, Set<String>> owners = new LinkedHashMap<>();
        Baseline.entries(BASELINE).forEach(entry -> owners.put(
                Baseline.column(entry, 0),
                new LinkedHashSet<>(List.of(Baseline.column(entry, 1).split(",")))));
        return owners;
    }
}
