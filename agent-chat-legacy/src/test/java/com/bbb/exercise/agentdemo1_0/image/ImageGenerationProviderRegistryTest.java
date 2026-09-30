package com.bbb.exercise.agentdemo1_0.image;

import com.bbb.exercise.agentdemo1_0.model.ModelProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageGenerationProviderRegistryTest {
    @Test
    void resolvesConfiguredProvidersAndRejectsUnknownProvider() {
        ImageGenerationProviderRegistry registry = ImageGenerationProviderRegistry.defaultRegistry();
        assertThat(registry.resolve(ModelProvider.QWEN).provider()).isEqualTo(ModelProvider.QWEN);
        assertThat(registry.resolve(ModelProvider.GPT).provider()).isEqualTo(ModelProvider.GPT);
        assertThatThrownBy(() -> registry.resolve(ModelProvider.GEMINI))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持图片生成");
    }
}
