package com.bbb.exercise.agentdemo.mediaservice;

import com.bbb.exercise.agentdemo.mediaservice.config.ZineProperties;
import com.bbb.exercise.agentdemo.mediaservice.provider.DashScopeImageGenerationClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 DashScope 客户端可直接根据运行时配置构造，无需额外 WebClient Bean。 */
class DashScopeClientConstructionTest {
    @Test
    void constructsFromRuntimePropertiesWithoutWebClientBean() {
        assertThat(DashScopeImageGenerationClient.class.getConstructors())
                .anyMatch(
                        constructor ->
                                constructor.getParameterCount() == 1
                                        && constructor.getParameterTypes()[0].equals(
                                                ZineProperties.class));
    }
}
