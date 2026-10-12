package com.bbb.exercise.agentdemo.auth;

import com.bbb.exercise.agentdemo.api.model.*;
import com.bbb.exercise.agentdemo.auth.model.ModelProfileService.SelectedModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Operator-owned routes. Credentials are isolated by provider and capability. */
@Component
public class PlatformModels {
    private final String apiKey,
            chat,
            vision,
            image,
            embedding,
            rerank,
            evaluation,
            fallback,
            visionFallback;
    private final String baseUrl, qwenKey, qwenUrl, rerankUrl, fallbackUrl, fallbackKey;
    private final ModelProvider primaryProvider, fallbackProvider;

    public PlatformModels(String apiKey, String chat, String vision, String image) {
        this(
                apiKey,
                chat,
                vision,
                image,
                "text-embedding-v4",
                "qwen3-rerank",
                chat,
                "qwen-plus",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "QWEN",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "");
    }

    public PlatformModels(
            String apiKey,
            String chat,
            String vision,
            String image,
            String embedding,
            String rerank,
            String evaluation,
            String fallback,
            String baseUrl,
            String rerankUrl,
            String fallbackProvider,
            String fallbackUrl,
            String fallbackKey) {
        this(
                apiKey,
                chat,
                vision,
                image,
                embedding,
                rerank,
                evaluation,
                fallback,
                baseUrl,
                rerankUrl,
                fallbackProvider,
                fallbackUrl,
                fallbackKey,
                "QWEN",
                apiKey,
                baseUrl,
                "qwen-vl-plus");
    }

    @Autowired
    public PlatformModels(
            @Value("${app.platform.api-key:}") String apiKey,
            @Value("${app.platform.chat-model:gpt-4.1}") String chat,
            @Value("${app.platform.vision-model:gpt-4.1}") String vision,
            @Value("${app.platform.image-model:qwen-image-3.0-pro}") String image,
            @Value("${app.platform.embedding-model:text-embedding-v4}") String embedding,
            @Value("${app.platform.rerank-model:qwen3-rerank}") String rerank,
            @Value("${app.platform.evaluation-model:gpt-4.1}") String evaluation,
            @Value("${app.platform.fallback-model:qwen-plus}") String fallback,
            @Value("${app.platform.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${app.platform.rerank-base-url:}") String rerankUrl,
            @Value("${app.platform.fallback-provider:QWEN}") String fallbackProvider,
            @Value(
                            "${app.platform.fallback-base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
                    String fallbackUrl,
            @Value("${app.platform.fallback-api-key:}") String fallbackKey,
            @Value("${app.platform.provider:GPT}") String primaryProvider,
            @Value("${app.platform.qwen-api-key:}") String qwenKey,
            @Value(
                            "${app.platform.qwen-base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
                    String qwenUrl,
            @Value("${app.platform.vision-fallback-model:qwen-vl-plus}") String visionFallback) {
        this.apiKey = apiKey;
        this.chat = chat;
        this.vision = vision;
        this.image = image;
        this.embedding = embedding;
        this.rerank = rerank;
        this.evaluation = evaluation;
        this.fallback = fallback;
        this.visionFallback = visionFallback;
        this.baseUrl = normalize(baseUrl);
        this.qwenKey = qwenKey;
        this.qwenUrl = normalize(qwenUrl);
        this.rerankUrl = normalize(rerankUrl);
        this.fallbackUrl = normalize(fallbackUrl);
        this.fallbackKey = fallbackKey;
        this.primaryProvider = ModelProvider.parse(primaryProvider);
        this.fallbackProvider = ModelProvider.parse(fallbackProvider);
        var registry = ModelProviderRegistry.defaultRegistry();

        registry.resolve(this.primaryProvider, ModelCapability.CHAT, chat);
        registry.resolve(this.primaryProvider, ModelCapability.VISION, vision);
        registry.resolve(this.fallbackProvider, ModelCapability.FALLBACK, fallback);
        registry.resolve(this.fallbackProvider, ModelCapability.VISION_FALLBACK, visionFallback);

        if (this.primaryProvider == this.fallbackProvider
                && chat.equals(fallback)
                && this.baseUrl.equals(this.fallbackUrl)) {
            throw new IllegalArgumentException("主备模型配置必须不同");
        }
    }

    public boolean configured() {
        return !apiKey.isBlank();
    }

    public String key() {
        return requireKey(apiKey);
    }

    // 1. 平台模型选择：按聊天、视觉、图片、向量、排序和评估能力返回统一配置。
    public SelectedModel select(ModelCapability capability) {
        if (capability == ModelCapability.RERANK && rerankUrl.isBlank()) {
            throw new AuthService.AuthException(503, "请配置所在区域的 qwen3-rerank workspace endpoint");
        }

        String model =
                switch (capability) {
                    case CHAT -> chat;
                    case VISION -> vision;
                    case IMAGE -> image;
                    case EMBEDDING -> embedding;
                    case RERANK -> rerank;
                    case EVALUATION -> evaluation;
                    case FALLBACK -> fallback;
                    case VISION_FALLBACK -> visionFallback;
                };
        boolean backup =
                capability == ModelCapability.FALLBACK
                        || capability == ModelCapability.VISION_FALLBACK;
        boolean qwen =
                capability == ModelCapability.IMAGE
                        || capability == ModelCapability.EMBEDDING
                        || capability == ModelCapability.RERANK;
        ModelProvider provider =
                backup ? fallbackProvider : qwen ? ModelProvider.QWEN : primaryProvider;
        String selectedKey =
                backup && !fallbackKey.isBlank()
                        ? fallbackKey
                        : provider == primaryProvider
                                ? apiKey
                                : provider == ModelProvider.QWEN ? qwenCredential() : "";
        String endpoint =
                backup
                        ? fallbackUrl
                        : capability == ModelCapability.RERANK
                                ? rerankUrl
                                : qwen ? qwenUrl : baseUrl;

        selectedKey = requireKey(selectedKey);

        return new SelectedModel(
                provider, capability, model, selectedKey, endpoint, credentialId(selectedKey));
    }

    private String qwenCredential() {
        return !qwenKey.isBlank()
                ? qwenKey
                : fallbackProvider == ModelProvider.QWEN ? fallbackKey : "";
    }

    // 2. 凭据就绪检查：缺少配置时明确失败，避免向供应商发送空密钥。
    private static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new AuthService.AuthException(503, "平台模型凭据尚未配置");
        }

        return key;
    }

    private static String normalize(String url) {
        return url == null ? "" : url.strip().replaceAll("/+$", "");
    }

    // 2.1. 凭据身份：使用派生标识参与模型快照，不把密钥正文写入业务标识。
    private static String credentialId(String key) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
