package com.bbb.exercise.agentdemo.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code agent-common} 的模块归属门禁（设计文档 §6.1）。
 *
 * <p>§6.1 规定 agent-common 只保存"无业务归属的公共能力"，
 * 并明确禁止放入业务实体、JdbcTemplate Repository、OSS 客户端或模型 Provider 实现。
 * 实测它现在有 27 个类，其中 9 个在合法的
 * {@code com.bbb.exercise.agentdemo.common} 包下（api / client / security / trace / web），
 * 另外 18 个仍在遗留根包 {@code com.bbb.exercise.agentdemo1_0} 下——包括
 * {@code HealthController}({@code @RestController})、{@code DashScopeImageGenerationClient}(Provider 实现)、
 * {@code AppProperties}/{@code OssProperties}/{@code ZineProperties}。
 *
 * <p>这 18 个类是 Phase B（关闭根包扫描）的<b>硬依赖</b>：不把它们归到
 * {@code com.bbb.exercise.agentdemo.common.*} 或各领域模块，扫描就关不掉。
 * 但"改归属"会动多个服务的 Bean 可见性，不能靠一次门禁变红逼出来，
 * 因此按混合策略分两档：
 *
 * <ul>
 *     <li><b>立即变红</b>：{@code WEB_LAYER} / {@code PERSISTENCE} / {@code OUTSIDE_COMMON_PACKAGE}
 *     三类今天都是 0 条。它们存在的意义是<b>堵住后门</b>——否则只要把业务类塞进
 *     {@code com.bbb.exercise.agentdemo.common} 就能宣称"已经归位"。</li>
 *     <li><b>基线快照</b>：{@code LEGACY_ROOT_PACKAGE} 18 条，只拦截"新增"，不拦截"减少"。
 *     2026-10-01 Phase B 第 1 批已把 {@code config/WebConfig} 迁到 {@code common.web}，
 *     该条从 19 收缩到 18。</li>
 * </ul>
 */
class CommonModuleBoundarySnapshotGateTest {

    private static final String BASELINE = "common-module-boundary.txt";

    @Test
    @DisplayName("合法公共包内不得出现 Web 层 / 持久层 / 包外越界")
    void hardBoundaryViolationsAreAbsent() {
        List<String> hard = ArchitectureState.commonModuleBoundaryViolations().stream()
                .filter(entry -> ArchitectureState.HARD_COMMON_CATEGORIES.contains(Baseline.column(entry, 1)))
                .toList();

        assertThat(hard)
                .as("agent-common 只能放无业务归属的公共能力。Web 层类归各服务，持久化归 owner 模块，"
                        + "不要塞进 com.bbb.exercise.agentdemo.common（格式：模块内路径|类别）")
                .isEmpty();
    }

    @Test
    @DisplayName("基线不得登记硬类别（防止把立即变红规则偷偷降级为快照）")
    void baselineMustNotDowngradeHardRules() {
        List<String> downgraded = Baseline.entries(BASELINE).stream()
                .filter(entry -> ArchitectureState.HARD_COMMON_CATEGORIES.contains(Baseline.column(entry, 1)))
                .toList();

        assertThat(downgraded)
                .as("%s 只能登记 LEGACY_ROOT_PACKAGE 这一档。把硬类别写进基线，"
                        + "等于让「立即变红」规则失效", BASELINE)
                .isEmpty();
    }

    @Test
    @DisplayName("agent-common 的归属越界清单只允许收缩")
    void noNewBoundaryViolation() {
        List<String> current = ArchitectureState.commonModuleBoundaryViolations();
        Set<String> registered = new LinkedHashSet<>(Baseline.entries(BASELINE));

        List<String> added = new ArrayList<>();
        for (String entry : current) {
            if (!registered.contains(entry)) {
                added.add("新增归属越界：" + entry);
            }
        }

        assertThat(added)
                .as("agent-common 不允许再新增归属越界的类；要收敛请同步精简 %s "
                        + "（可用 BaselineDumpTest 打印当前实际状态）", BASELINE)
                .isEmpty();

        List<String> resolved = registered.stream().filter(entry -> !current.contains(entry)).toList();
        if (!resolved.isEmpty()) {
            System.out.println("基线可收缩：" + BASELINE + " 已消除 " + resolved.size()
                    + " 条 → " + resolved);
        }
    }

    @Test
    @DisplayName("基线里的越界文件仍然存在（避免基线变成幽灵条目）")
    void baselineEntriesStillExist() {
        for (String entry : Baseline.entries(BASELINE)) {
            String path = Baseline.column(entry, 0);
            assertThat(Repository.exists("agent-common/" + path))
                    .as("基线登记的越界文件 %s 已不存在。若确实迁走了，请同步精简 %s；"
                            + "若只是想让它「安静」，那是改错了地方", path, BASELINE)
                    .isTrue();
        }
    }
}
