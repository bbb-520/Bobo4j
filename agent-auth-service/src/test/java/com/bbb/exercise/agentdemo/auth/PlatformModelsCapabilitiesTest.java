package com.bbb.exercise.agentdemo.auth;
import com.bbb.exercise.agentdemo.api.model.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class PlatformModelsCapabilitiesTest {
    @Test void ragAndFallbackResolveOperatorDefaultsWithRedactedCredentials() {
        var models = new PlatformModels("secret", "qwen-turbo", "qwen-vl-plus", "qwen-image");
        assertThat(models.select(ModelCapability.EMBEDDING).model()).isEqualTo("text-embedding-v4");
        assertThat(models.select(ModelCapability.RERANK).model()).isEqualTo("qwen3-rerank");
        assertThat(models.select(ModelCapability.FALLBACK).model()).isEqualTo("qwen-plus");
        assertThat(models.select(ModelCapability.EVALUATION).apiKey()).isEqualTo("secret");
        assertThat(models.select(ModelCapability.FALLBACK).toString()).doesNotContain("secret");
    }
}
