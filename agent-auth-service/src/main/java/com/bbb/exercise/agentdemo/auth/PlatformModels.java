package com.bbb.exercise.agentdemo.auth;

import com.bbb.exercise.agentdemo.api.model.*;
import com.bbb.exercise.agentdemo.auth.model.ModelProfileService.SelectedModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Operator-owned Qwen routes; provider compatibility for public contracts lives in agent-api. */
@Component
public class PlatformModels {
    private final String apiKey, chat, vision, image, embedding, rerank, evaluation, fallback, visionFallback;
    private final String baseUrl, rerankUrl, fallbackUrl, fallbackKey;

    public PlatformModels(
            @Value("${app.platform.qwen-api-key:}") String apiKey,
            @Value("${app.platform.chat-model:qwen-plus}") String chat,
            @Value("${app.platform.vision-model:qwen-vl-max}") String vision,
            @Value("${app.platform.image-model:qwen-image-3.0-pro}") String image,
            @Value("${app.platform.embedding-model:text-embedding-v4}") String embedding,
            @Value("${app.platform.rerank-model:qwen3-rerank}") String rerank,
            @Value("${app.platform.evaluation-model:qwen-plus}") String evaluation,
            @Value("${app.platform.fallback-model:qwen-turbo}") String fallback,
            @Value("${app.platform.qwen-base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
            @Value("${app.platform.rerank-base-url:}") String rerankUrl,
            @Value("${app.platform.fallback-base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String fallbackUrl,
            @Value("${app.platform.fallback-api-key:}") String fallbackKey,
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
        this.rerankUrl = normalize(rerankUrl);
        this.fallbackUrl = normalize(fallbackUrl);
        this.fallbackKey = fallbackKey;

        var registry = ModelProviderRegistry.defaultRegistry();
        for (var entry : java.util.Map.of(
                ModelCapability.CHAT, chat, ModelCapability.VISION, vision,
                ModelCapability.EVALUATION, evaluation, ModelCapability.FALLBACK, fallback,
                ModelCapability.VISION_FALLBACK, visionFallback).entrySet()) {
            registry.resolve(ModelProvider.QWEN, entry.getKey(), entry.getValue());
            if (!entry.getValue().strip().toLowerCase(java.util.Locale.ROOT).startsWith("qwen")) {
                throw new IllegalArgumentException("平台文本与视觉模型必须使用千问");
            }
        }
        if (this.baseUrl.equals(this.fallbackUrl)
                && (chat.equals(fallback) || vision.equals(visionFallback))) {
            throw new IllegalArgumentException("主备文本和视觉模型配置必须分别不同");
        }
    }

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String key() {
        return requireKey(apiKey);
    }

    /** Resolve each capability without borrowing credentials from retired platform providers. */
    public SelectedModel select(ModelCapability capability) {
        if (capability == ModelCapability.RERANK && rerankUrl.isBlank()) {
            throw new AuthService.AuthException(503, "请配置所在区域的 qwen3-rerank workspace endpoint");
        }
        String model = switch (capability) {
            case CHAT -> chat;
            case VISION -> vision;
            case IMAGE -> image;
            case EMBEDDING -> embedding;
            case RERANK -> rerank;
            case EVALUATION -> evaluation;
            case FALLBACK -> fallback;
            case VISION_FALLBACK -> visionFallback;
        };
        boolean backup = capability == ModelCapability.FALLBACK || capability == ModelCapability.VISION_FALLBACK;
        String selectedKey = requireKey(backup && fallbackKey != null && !fallbackKey.isBlank() ? fallbackKey : apiKey);
        String endpoint = backup ? fallbackUrl : capability == ModelCapability.RERANK ? rerankUrl : baseUrl;
        return new SelectedModel(ModelProvider.QWEN, capability, model, selectedKey, endpoint, credentialId(selectedKey));
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new AuthService.AuthException(503, "平台模型凭据尚未配置");
        }
        return key;
    }

    private static String normalize(String url) {
        return url == null ? "" : url.strip().replaceAll("/+$", "");
    }

    private static String credentialId(String key) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
