package com.bbb.exercise.agentdemo.auth;

import com.bbb.exercise.agentdemo.api.model.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 验证平台托管模型按能力选择供应商，并隔离和隐藏不同路由的凭据。 */
class PlatformModelsCapabilitiesTest {
    @Test
    void managedDefaultsRouteGenerationToGptWithIndependentQwenRetrievalAndFallbackKeys() {
        try (var context =
                new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getEnvironment()
                    .getPropertySources()
                    .addFirst(
                            new org.springframework.core.env.MapPropertySource(
                                    "test",
                                    java.util.Map.of(
                                            "app.platform.api-key",
                                            "openai-secret",
                                            "app.platform.qwen-api-key",
                                            "qwen-secret",
                                            "app.platform.rerank-base-url",
                                            "https://rerank.example/v1")));
            context.register(PlatformModels.class);
            context.refresh();
            var models = context.getBean(PlatformModels.class);

            for (var capability :
                    java.util.List.of(
                            ModelCapability.CHAT,
                            ModelCapability.VISION,
                            ModelCapability.EVALUATION)) {
                var selected = models.select(capability);

                assertThat(selected.provider()).isEqualTo(ModelProvider.GPT);
                assertThat(selected.apiKey()).isEqualTo("openai-secret");
                assertThat(selected.baseUrl()).isEqualTo("https://api.openai.com/v1");
            }

            for (var capability :
                    java.util.List.of(
                            ModelCapability.EMBEDDING,
                            ModelCapability.RERANK,
                            ModelCapability.IMAGE,
                            ModelCapability.FALLBACK)) {
                var selected = models.select(capability);

                assertThat(selected.provider()).isEqualTo(ModelProvider.QWEN);
                assertThat(selected.apiKey()).isEqualTo("qwen-secret");
            }
        }
    }

    @Test
    void ragAndFallbackResolveOperatorDefaultsWithRedactedCredentials() {
        var models = new PlatformModels("secret", "qwen-turbo", "qwen-vl-plus", "qwen-image");

        assertThat(models.select(ModelCapability.EMBEDDING).model()).isEqualTo("text-embedding-v4");
        assertThat(models.select(ModelCapability.RERANK).model()).isEqualTo("qwen3-rerank");
        assertThat(models.select(ModelCapability.FALLBACK).model()).isEqualTo("qwen-plus");
        assertThat(models.select(ModelCapability.EVALUATION).apiKey()).isEqualTo("secret");
        assertThat(models.select(ModelCapability.FALLBACK).toString()).doesNotContain("secret");
    }
}
