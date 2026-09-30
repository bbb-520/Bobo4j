package com.bbb.exercise.agentdemo1_0.image;

import com.bbb.exercise.agentdemo1_0.model.ModelProvider;
import com.bbb.exercise.agentdemo1_0.zine.DashScopeImageGenerationClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/** Registry used by the worker so provider selection is data-driven, not hard-coded. */
@Component
public final class ImageGenerationProviderRegistry {
    private final Map<ModelProvider, ImageGenerationProvider> providers;

    @Autowired
    public ImageGenerationProviderRegistry(DashScopeImageGenerationClient dashScope) {
        EnumMap<ModelProvider, ImageGenerationProvider> values = new EnumMap<>(ModelProvider.class);
        values.put(ModelProvider.QWEN, new DashScopeImageGenerationProvider(dashScope));
        values.put(ModelProvider.GPT, new OpenAiCompatibleImageGenerationProvider(ModelProvider.GPT, "https://api.openai.com/v1"));
        values.put(ModelProvider.GLM, new OpenAiCompatibleImageGenerationProvider(ModelProvider.GLM, "https://open.bigmodel.cn/api/paas/v4"));
        values.put(ModelProvider.HY, new OpenAiCompatibleImageGenerationProvider(ModelProvider.HY, "https://api.hunyuan.cloud.tencent.com/v1"));
        this.providers = Map.copyOf(values);
    }

    private ImageGenerationProviderRegistry(Map<ModelProvider, ImageGenerationProvider> providers) {
        this.providers = Map.copyOf(providers);
    }

    public static ImageGenerationProviderRegistry defaultRegistry() {
        EnumMap<ModelProvider, ImageGenerationProvider> values = new EnumMap<>(ModelProvider.class);
        values.put(ModelProvider.QWEN, new DashScopeImageGenerationProvider(null));
        values.put(ModelProvider.GPT, new OpenAiCompatibleImageGenerationProvider(ModelProvider.GPT, "https://api.openai.com/v1"));
        values.put(ModelProvider.GLM, new OpenAiCompatibleImageGenerationProvider(ModelProvider.GLM, "https://open.bigmodel.cn/api/paas/v4"));
        values.put(ModelProvider.HY, new OpenAiCompatibleImageGenerationProvider(ModelProvider.HY, "https://api.hunyuan.cloud.tencent.com/v1"));
        return new ImageGenerationProviderRegistry(values);
    }

    public ImageGenerationProvider resolve(ModelProvider provider) {
        ImageGenerationProvider result = providers.get(provider);
        if (result == null) throw new IllegalArgumentException("Provider " + provider + " 不支持图片生成");
        return result;
    }
}
