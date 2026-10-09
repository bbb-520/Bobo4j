package com.bbb.exercise.agentdemo.mediaservice.image;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ImageJobServiceContractTest {
    @Test
    void hasOnlyTheWorkerScopedClaimEntryPoint() {
        assertThat(Arrays.stream(ImageJobService.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("claimNext")))
                .hasSize(1);
    }
}
