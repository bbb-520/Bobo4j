package com.bbb.exercise.agentdemo.api.model;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Pure model-provider catalog; each runtime service creates its own bean. */
public final class ModelProviderRegistry {
    private final Map<ModelProvider, ProviderDescriptor> descriptors;

    public ModelProviderRegistry() {
        this(defaultRegistry().descriptors);
    }

    public ModelProviderRegistry(Map<ModelProvider, ProviderDescriptor> descriptors) {
        this.descriptors = Map.copyOf(descriptors);
    }

    public static ModelProviderRegistry defaultRegistry() {
        EnumMap<ModelProvider, ProviderDescriptor> values = new EnumMap<>(ModelProvider.class);
        values.put(ModelProvider.GPT, descriptor(ModelProvider.GPT, "https://api.openai.com/v1", ModelCapability.CHAT, ModelCapability.VISION, ModelCapability.IMAGE, ModelCapability.EMBEDDING, ModelCapability.EVALUATION, ModelCapability.FALLBACK));
        values.put(ModelProvider.GEMINI, descriptor(ModelProvider.GEMINI, "https://generativelanguage.googleapis.com/v1beta/openai/", ModelCapability.CHAT, ModelCapability.VISION, ModelCapability.EMBEDDING));
        values.put(ModelProvider.QWEN, descriptor(ModelProvider.QWEN, "https://dashscope.aliyuncs.com/compatible-mode/v1", ModelCapability.CHAT, ModelCapability.VISION, ModelCapability.IMAGE, ModelCapability.EMBEDDING, ModelCapability.RERANK, ModelCapability.EVALUATION, ModelCapability.FALLBACK));
        values.put(ModelProvider.GLM, descriptor(ModelProvider.GLM, "https://open.bigmodel.cn/api/paas/v4", ModelCapability.CHAT, ModelCapability.VISION, ModelCapability.IMAGE, ModelCapability.EMBEDDING));
        values.put(ModelProvider.HY, descriptor(ModelProvider.HY, "https://api.hunyuan.cloud.tencent.com/v1", ModelCapability.CHAT, ModelCapability.VISION, ModelCapability.IMAGE, ModelCapability.EMBEDDING));
        return new ModelProviderRegistry(values);
    }

    public Set<ModelProvider> providers() { return descriptors.keySet(); }

    public ProviderDescriptor resolve(ModelProvider provider, ModelCapability capability, String model) {
        if (provider == null || capability == null) throw new IllegalArgumentException("Provider 和模型能力不能为空");
        ProviderDescriptor descriptor = descriptors.get(provider);
        if (descriptor == null || !descriptor.capabilities().contains(capability)) {
            throw new IllegalArgumentException("Provider " + provider + " 不支持模型能力 " + capability);
        }
        if (model == null || model.isBlank()) throw new IllegalArgumentException("模型名称不能为空");
        return descriptor;
    }

    private static ProviderDescriptor descriptor(ModelProvider provider, String url, ModelCapability... capabilities) {
        return new ProviderDescriptor(provider, EnumSet.copyOf(Set.of(capabilities)), true, url);
    }

    public record ProviderDescriptor(ModelProvider provider, Set<ModelCapability> capabilities,
                                     boolean openAiCompatible, String defaultBaseUrl) {
        public ProviderDescriptor { capabilities = Set.copyOf(capabilities); }
    }
}
