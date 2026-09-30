package com.bbb.exercise.agentdemo.mediaservice;

import com.bbb.exercise.agentdemo1_0.config.ZineProperties;
import com.bbb.exercise.agentdemo1_0.zine.DashScopeImageGenerationClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.assertj.core.api.Assertions.assertThat;

class DashScopeClientConstructionTest {
    @Test
    void constructsFromRuntimePropertiesWithoutWebClientBean() {
        assertThat(DashScopeImageGenerationClient.class.getConstructors())
                .anyMatch(constructor -> constructor.getParameterCount() == 1
                        && constructor.getParameterTypes()[0].equals(ZineProperties.class));
    }
}
