package com.bbb.exercise.agentdemo.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 快照门禁：{@code agent-api} 的每个契约都必须有真实调用方，或者被显式登记为待清理。
 *
 * <p>设计文档 §16 要求 {@code agent-api} 只承担"稳定的跨服务契约"。
 * 现实里 {@code MediaInternalApi} 已经 0 引用、{@code AuthInternalApi} 只被 Feign 注册从未注入，
 * 都属于"看起来是契约、其实是死代码"的状态。它们不应该悄悄存在，也不该被一次性删掉
 * （可能是下一个 Phase 要接上的口子），所以走登记制：
 * <ul>
 *     <li>新增契约 → 必须登记（哪怕它有调用方），强制作者想一次"这是谁用的"；</li>
 *     <li>新增 0 引用的契约 → 必须登记为 DEAD，否则变红。</li>
 * </ul>
 */
class DeadApiContractSnapshotGateTest {

    private static final String BASELINE = "api-contracts.txt";

    @Test
    @DisplayName("agent-api 的契约清单与基线一一对应（新增契约必须登记）")
    void everyContractIsRegistered() {
        Set<String> registered = new LinkedHashSet<>(Baseline.keys(BASELINE));
        Set<String> current = new LinkedHashSet<>(ArchitectureState.apiContractExternalReferences().keySet());

        List<String> unregistered = current.stream().filter(fqn -> !registered.contains(fqn)).toList();
        assertThat(unregistered)
                .as("新增契约必须登记到 %s，并在基线第二列写清外部引用文件数", BASELINE)
                .isEmpty();

        List<String> stale = registered.stream().filter(fqn -> !current.contains(fqn)).toList();
        assertThat(stale)
                .as("基线里登记了 agent-api 已不存在的契约，请同步删除")
                .isEmpty();
    }

    @Test
    @DisplayName("没有零外部引用的契约游离在登记表之外")
    void deadContractsMustBeRegisteredAsDead() {
        Map<String, String> status = new java.util.TreeMap<>();
        Baseline.entries(BASELINE).forEach(entry ->
                status.put(Baseline.column(entry, 0), Baseline.column(entry, 2)));

        List<String> unregistered = new ArrayList<>();
        ArchitectureState.apiContractExternalReferences().forEach((fqn, references) -> {
            if (references == 0 && !"DEAD".equals(status.get(fqn))) {
                unregistered.add(fqn + " 外部引用 0 次，但基线状态是 " + status.get(fqn));
            }
        });

        assertThat(unregistered)
                .as("零引用契约要么接上调用方，要么在 %s 里明确标注 DEAD", BASELINE)
                .isEmpty();
    }

    @Test
    @DisplayName("契约的外部引用数发生变化时输出提示，便于同步基线")
    void reportReferenceCountDrift() {
        Map<String, Integer> current = ArchitectureState.apiContractExternalReferences();
        List<String> drift = new ArrayList<>();
        Baseline.entries(BASELINE).forEach(entry -> {
            String fqn = Baseline.column(entry, 0);
            String recorded = Baseline.column(entry, 1);
            Integer actual = current.get(fqn);
            if (actual != null && !String.valueOf(actual).equals(recorded)) {
                drift.add(fqn + "：基线记录 " + recorded + " 次，当前 " + actual + " 次");
            }
        });

        if (!drift.isEmpty()) {
            System.out.println("契约引用数漂移（不判失败，但建议同步 " + BASELINE + "）→ " + drift);
        }
    }
}
