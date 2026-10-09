package com.bbb.exercise.agentdemo.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 快照门禁：README、模块配置、Nacos 导入三处的服务名与端口必须完全一致。
 *
 * <p>Phase 0 抓到的真实事故：{@code infra/nacos/import/agent-chat-service.yml} 写的是 Legacy 的
 * {@code server.port: 18080}，而由于 {@code spring.config.import} 优先级更高，
 * 新 Chat 服务会去抢 Legacy 的端口。这类漂移不会让任何单元测试变红，
 * 只会在部署时炸掉，所以必须由门禁兜住。
 *
 * <p>判据只有一条：**同一份事实只允许有一个数字**。README 端口表、模块
 * {@code application.yml} 的默认端口、Nacos 导入配置的端口，三者必须相等。
 */
class DeploymentDescriptorDriftGateTest {

    /** 会以独立进程运行的模块。 */
    private static final List<String> RUNTIME_MODULES = List.of(
            "agent-gateway",
            "agent-auth-service",
            "agent-chat-service",
            "agent-rag-service",
            "agent-media-service",
            "agent-content-service",
            "agent-orchestrator-service");

    @Test
    @DisplayName("README 端口表覆盖全部运行模块，且与模块 application.yml 默认端口一致")
    void readmePortTableMatchesModuleDefaults() {
        Map<String, Integer> readme = ArchitectureState.readmeModulePorts();

        assertThat(readme.keySet())
                .as("README「领域服务」端口表必须列出全部运行模块")
                .containsExactlyInAnyOrderElementsOf(RUNTIME_MODULES);

        List<String> drift = new ArrayList<>();
        for (String module : RUNTIME_MODULES) {
            OptionalInt declared = ArchitectureState.declaredServerPort(
                    Repository.resolve(module + "/src/main/resources/application.yml"));
            assertThat(declared).as("%s 必须声明 server.port", module).isPresent();
            if (declared.getAsInt() != readme.get(module)) {
                drift.add(module + "：README " + readme.get(module) + " vs application.yml " + declared.getAsInt());
            }
        }

        assertThat(drift).as("README 端口表必须与代码实测一致").isEmpty();
    }

    @Test
    @DisplayName("README 顶层架构图的端口与端口表一致")
    void readmeTopologyMatchesPortTable() {
        Map<String, Integer> topology = ArchitectureState.readmeTopologyPorts();
        Map<String, Integer> table = ArchitectureState.readmeModulePorts();

        assertThat(topology).as("README 顶层架构图必须画出门户服务").containsKey("agent-gateway");

        List<String> drift = new ArrayList<>();
        topology.forEach((service, port) -> {
            Integer expected = table.get(service);
            if (expected != null && !expected.equals(port)) {
                drift.add(service + "：架构图 " + port + " vs 端口表 " + expected);
            }
        });

        assertThat(drift).as("README 两处端口不能自相矛盾").isEmpty();
    }

    @Test
    @DisplayName("Nacos 导入配置的服务名与端口必须与模块一致")
    void nacosImportsMatchModules() {
        List<String> drift = new ArrayList<>();
        for (String module : RUNTIME_MODULES) {
            Path imported = Repository.resolve("infra/nacos/import/" + module + ".yml");
            assertThat(imported)
                    .as("%s 必须有对应的 Nacos 导入配置，否则服务名/端口会走默认值", module)
                    .exists();

            if (!module.equals(ArchitectureState.declaredApplicationName(imported))) {
                drift.add(module + " 的 Nacos 配置声明了错误的服务名："
                        + ArchitectureState.declaredApplicationName(imported));
            }
            OptionalInt nacosPort = ArchitectureState.declaredServerPort(imported);
            if (nacosPort.isPresent()
                    && nacosPort.getAsInt() != ArchitectureState.readmeModulePorts().get(module)) {
                drift.add(module + " 的 Nacos 配置端口 " + nacosPort.getAsInt()
                        + " 与模块默认端口不一致（Nacos 优先级更高，会直接抢占端口）");
            }
        }

        assertThat(drift).as("Nacos 导入配置必须与模块默认值一致").isEmpty();
    }

    @Test
    @DisplayName("模块声明的服务名与模块目录名一致")
    void moduleApplicationNamesMatchModuleDirectories() {
        List<String> drift = new ArrayList<>();
        for (String module : RUNTIME_MODULES) {
            String name = ArchitectureState.declaredApplicationName(
                    Repository.resolve(module + "/src/main/resources/application.yml"));
            if (!module.equals(name)) {
                drift.add(module + " 声明了服务名 " + name);
            }
        }

        assertThat(drift).as("发现服务名不是模块目录名（discovery 名与路由目标必须能对应上）").isEmpty();
    }

    @Test
    @DisplayName("运行端口互不冲突")
    void portsAreUnique() {
        Map<Integer, String> seen = new LinkedHashMap<>();
        List<String> conflicts = new ArrayList<>();
        ArchitectureState.readmeModulePorts().forEach((module, port) -> {
            String previous = seen.put(port, module);
            if (previous != null) {
                conflicts.add(port + " 被 " + previous + " 与 " + module + " 同时占用");
            }
        });

        assertThat(conflicts).as("端口重复会在本地/容器同时启动时直接失败").isEmpty();
    }

}
