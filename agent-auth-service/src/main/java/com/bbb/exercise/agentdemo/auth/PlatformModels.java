package com.bbb.exercise.agentdemo.auth;

import com.bbb.exercise.agentdemo.api.model.*;
import com.bbb.exercise.agentdemo.auth.model.ModelProfileService.SelectedModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/** Only the operator can select models or configure a provider key. */
@Component
public class PlatformModels {
    private final String apiKey, chat, vision, image,embedding,rerank,evaluation,fallback,baseUrl,rerankUrl,fallbackUrl,fallbackKey;
    private final ModelProvider fallbackProvider;
    public PlatformModels(@Value("${app.platform.api-key:}") String apiKey,
                          @Value("${app.platform.chat-model:qwen-turbo}") String chat,
                          @Value("${app.platform.vision-model:qwen-vl-plus}") String vision,
                          @Value("${app.platform.image-model:qwen-image-3.0-pro}") String image) {
        this(apiKey,chat,vision,image,"text-embedding-v4","qwen3-rerank",chat,"qwen-plus","https://dashscope.aliyuncs.com/compatible-mode/v1","https://dashscope.aliyuncs.com/compatible-mode/v1", "QWEN","https://dashscope.aliyuncs.com/compatible-mode/v1","");
    }
    @Autowired
    public PlatformModels(@Value("${app.platform.api-key:}") String apiKey,
            @Value("${app.platform.chat-model:qwen-turbo}") String chat,
            @Value("${app.platform.vision-model:qwen-vl-plus}") String vision,
            @Value("${app.platform.image-model:qwen-image-3.0-pro}") String image,
            @Value("${app.platform.embedding-model:text-embedding-v4}") String embedding,
            @Value("${app.platform.rerank-model:qwen3-rerank}") String rerank,
            @Value("${app.platform.evaluation-model:qwen-plus}") String evaluation,
            @Value("${app.platform.fallback-model:qwen-plus}") String fallback,
            @Value("${app.platform.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
            @Value("${app.platform.rerank-base-url:}") String rerankUrl,
            @Value("${app.platform.fallback-provider:QWEN}") String fallbackProvider,
            @Value("${app.platform.fallback-base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String fallbackUrl,
            @Value("${app.platform.fallback-api-key:}") String fallbackKey) {
        this.apiKey=apiKey;this.chat=chat;this.vision=vision;this.image=image;this.embedding=embedding;this.rerank=rerank;this.evaluation=evaluation;this.fallback=fallback;this.baseUrl=baseUrl;this.rerankUrl=rerankUrl;this.fallbackUrl=fallbackUrl;this.fallbackKey=fallbackKey;
        this.fallbackProvider=ModelProvider.parse(fallbackProvider);
        ModelProviderRegistry.defaultRegistry().resolve(this.fallbackProvider,ModelCapability.FALLBACK,fallback);
        if(this.fallbackProvider==ModelProvider.QWEN&&chat.equals(fallback)&&baseUrl.equals(fallbackUrl))throw new IllegalArgumentException("主备模型配置必须不同");
    }
    public boolean configured() {return !apiKey.isBlank();}
    public String key() {if(!configured()) throw new AuthService.AuthException(503,"平台模型凭据尚未配置");return apiKey;}
    public SelectedModel select(ModelCapability capability) {
        if(capability==ModelCapability.RERANK&&rerankUrl.isBlank())throw new AuthService.AuthException(503,"请配置所在区域的 qwen3-rerank workspace endpoint");
        String model=switch(capability) {case CHAT -> chat;case VISION -> vision;case IMAGE -> image;case EMBEDDING -> embedding;case RERANK -> rerank;case EVALUATION -> evaluation;case FALLBACK -> fallback;};
        boolean backup=capability==ModelCapability.FALLBACK;
        String selectedKey=backup&&!fallbackKey.isBlank()?fallbackKey:key();
        String endpoint=backup?fallbackUrl:capability==ModelCapability.RERANK?rerankUrl:baseUrl;
        return new SelectedModel(backup?fallbackProvider:ModelProvider.QWEN,capability,model,selectedKey,endpoint.replaceAll("/+$",""),credentialId(selectedKey));
    }
    private static String credentialId(String key) {try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
