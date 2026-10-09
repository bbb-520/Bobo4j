package com.bbb.exercise.agentdemo.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 设计文档 §15.2：「Controller 不直接依赖 JdbcTemplate」。
 *
 * <p>这类违规不会让任何功能测试变红——它能跑通，只是把持久化细节焊死在 Web 层，
 * 于是"换一个 owner"、"加一层权限过滤"都要改 Controller。今天全部服务都是干净的 0 条，
 * 所以这里是<b>立即变红</b>门禁，不给基线。
 *
 * <p>判据只看 {@code import} 语句和 {@code @Mapper} 注解，不扫整类文本：
 * 注释里写"这里不要用 JdbcTemplate"不该让门禁变红，而真的直接依赖它的人
 * 必然有对应的 import。
 */
class ControllerPersistenceBoundaryGateTest {

    @Test
    @DisplayName("没有 Web 层类直接依赖持久化能力")
    void controllersMustNotDependOnPersistenceDirectly() {
        List<String> violations = ArchitectureState.controllersDependingOnPersistence();

        assertThat(violations)
                .as("Web 层不能直接拿持久化能力。请把数据访问下移到 application/domain 层的组件，"
                        + "Controller 只依赖 Service（格式：模块|模块内路径|命中原因）")
                .isEmpty();
    }

    @Test
    @DisplayName("门禁确实扫到了 Web 层类（避免规则空跑）")
    void gateActuallyScannedWebLayerClasses() {
        long webLayerClasses = Repository.javaSourcesOfAllModules(false).stream()
                .filter(file -> Repository.text(file).contains("@RestController")
                        || Repository.text(file).contains("@Controller"))
                .count();

        assertThat(webLayerClasses)
                .as("一个 Controller 都没扫到，说明源码枚举范围出错，这条门禁等于没跑")
                .isGreaterThan(5);
    }
}
