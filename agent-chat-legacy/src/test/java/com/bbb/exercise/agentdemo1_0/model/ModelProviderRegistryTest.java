package com.bbb.exercise.agentdemo1_0.model;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ModelProviderRegistryTest {

    @Test
    void catalogIncludesConfiguredProvidersAndCapabilities() {
        ModelProviderRegistry registry = ModelProviderRegistry.defaultRegistry();

        assertEquals(
                Set.of(ModelProvider.GPT, ModelProvider.GEMINI, ModelProvider.QWEN,
                        ModelProvider.GLM, ModelProvider.HY),
                registry.providers());
        assertTrue(registry.resolve(ModelProvider.GPT, ModelCapability.CHAT, "gpt-4o").capabilities()
                .contains(ModelCapability.CHAT));
        assertTrue(registry.resolve(ModelProvider.GEMINI, ModelCapability.VISION, "gemini-2.5-pro")
                .capabilities().contains(ModelCapability.VISION));
    }

    @Test
    void unsupportedCapabilityProducesActionableError() {
        ModelProviderRegistry registry = ModelProviderRegistry.defaultRegistry();

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> registry.resolve(ModelProvider.GEMINI, ModelCapability.IMAGE, "gemini-image"));

        assertTrue(error.getMessage().contains("GEMINI"));
        assertTrue(error.getMessage().contains("IMAGE"));
    }

    @Test
    void providerAndSecretParsingIsCaseInsensitiveAndMasked() {
        assertEquals(ModelProvider.QWEN, ModelProvider.parse("qwen"));
        assertEquals(ModelCapability.VISION, ModelCapability.parse("Vision"));
        assertEquals("sk-a••••••••7890", ModelProfile.maskSecret("sk-abcdef1234567890"));
        assertEquals("••••••••", ModelProfile.maskSecret("short"));
    }
}
