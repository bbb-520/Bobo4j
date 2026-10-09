package com.bbb.exercise.agentdemo.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 闸门 4（立即变红，无基线）：一个业务能力只能有一条生产实现。
 *
 * <p>图片任务租约曾经有两套：{@code ImageJobService.claimNext} 与
 * {@code WorkerLeaseService}。两套 claim 同时存在于生产源码树，就迟早会出现
 * "同一个 image_job 被两个 Worker 同时抢到"的隐性竞态，而这类 bug 在测试里极难复现。
 * A0 已经删掉 {@code WorkerLeaseService}，本门禁负责让第二套实现不能再回来。
 *
 * <p>硬切换后全仓库只能保留当前 owner 的一条实现。
 */
class SingleClaimImplementationGateTest {

    @Test
    @DisplayName("每种持久任务只能由所属领域的一条 claim 实现领取")
    void productionModulesHaveExactlyOneClaimImplementation() {
        List<String> productionClaims = ArchitectureState.claimImplementationFiles();
        assertThat(productionClaims)
                .as("图片、Agent执行、文档作业各有唯一 owner，不能新增第二套领取实现")
                .containsExactlyInAnyOrder(
                    "agent-media-service|src/main/java/com/bbb/exercise/agentdemo/mediaservice/image/ImageJobService.java",
                    "agent-orchestrator-service|src/main/java/com/bbb/exercise/agentdemo/orchestrator/execution/ExecutionStore.java",
                    "agent-rag-service|src/main/java/com/bbb/exercise/agentdemo/ragservice/persistence/RagRepository.java");
    }

    @Test
    @DisplayName("claim 实现使用带 workerId 与租约时长的统一签名")
    void claimSignatureCarriesWorkerIdentityAndLease() {
        String source = Repository.text(Repository.resolve(
                "agent-media-service/src/main/java/com/bbb/exercise/agentdemo/mediaservice/image/ImageJobService.java"));

        assertThat(source)
                .as("统一 claim 必须能标识抢占者，否则无法审计是谁抢到的")
                .containsPattern("claimNext\\s*\\(\\s*String\\s+workerId\\s*,\\s*Duration\\s+leaseDuration\\s*\\)");
    }

    @Test
    @DisplayName("第二套租约实现不得回到生产源码树")
    void noSecondLeaseImplementation() {
        List<String> leaseClasses = Repository.PRODUCTION_MODULES.stream()
                .flatMap(module -> Repository.javaSources(module, false).stream())
                .map(Repository::relative)
                .filter(path -> path.endsWith("LeaseService.java"))
                .toList();

        assertThat(leaseClasses)
                .as("非 legacy 生产源码里不允许存在第二套租约/claim 实现")
                .isEmpty();

        assertThat(Repository.exists(
                "agent-media-service/src/main/java/com/bbb/exercise/agentdemo/mediaservice/worker/WorkerLeaseService.java"))
                .as("A0 已移除的 WorkerLeaseService 不得被重新加入")
                .isFalse();
    }

}
