package com.bbb.exercise.agentdemo.mediaservice.image;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证图片任务只保留带工作节点身份和租约的唯一领取入口。 */
class ImageJobServiceContractTest {
    @Test
    void hasOnlyTheWorkerScopedClaimEntryPoint() {
        assertThat(
                        Arrays.stream(ImageJobService.class.getDeclaredMethods())
                                .filter(method -> method.getName().equals("claimNext")))
                .hasSize(1);
    }
}
