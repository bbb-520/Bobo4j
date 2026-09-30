package com.bbb.exercise.agentdemo1_0.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VisionMemoryRankingTest {
    @Test
    void ranksMemoriesByQueryTokenOverlapAndRecency() {
        VisionMemoryService.Memory old = new VisionMemoryService.Memory(1L, "视觉偏好", "偏爱暖色、电影感构图", 1L);
        VisionMemoryService.Memory recent = new VisionMemoryService.Memory(2L, "视觉偏好", "偏爱蓝色、极简构图", 3L);
        assertThat(VisionMemoryService.score("蓝色旅行海报", recent)).isGreaterThan(
                VisionMemoryService.score("蓝色旅行海报", old));
    }
}
