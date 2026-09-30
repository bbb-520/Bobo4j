package com.bbb.exercise.agentdemo1_0.chat;

import com.bbb.exercise.agentdemo1_0.auth.UserApiKeyService.UserApiKeys;
import com.bbb.exercise.agentdemo1_0.model.ModelProfileService;
import com.bbb.exercise.agentdemo1_0.model.ModelProviderRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Builds an image-focused request-scoped ChatClient using the user's encrypted key. */
@Component
public class UserChatClientFactory {
    private final String systemPrompt;
    private final ChatMemory chatMemory;
    private final String baseUrl;
    private final String model;
    private final String visionModel;
    private final ModelProviderRegistry providers;

    public UserChatClientFactory(String systemPrompt, ChatMemory chatMemory,
                                 @Value("${spring.ai.openai.base-url}") String baseUrl,
                                 @Value("${spring.ai.openai.chat.model:qwen-turbo}") String model,
                                 @Value("${spring.ai.openai.chat.vision-model:qwen-vl-plus}") String visionModel,
                                 ModelProviderRegistry providers) {
        this.systemPrompt = systemPrompt;
        this.chatMemory = chatMemory;
        this.baseUrl = baseUrl;
        this.model = model;
        this.visionModel = visionModel;
        this.providers = providers;
    }

    public ChatClient create(UserApiKeys keys, boolean multimodal) {
        if (!keys.hasQwen()) throw new IllegalStateException("请先在用户页配置阿里云 API Key");
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .apiKey(keys.qwenApiKey())
                .baseUrl(baseUrl)
                .model(multimodal ? visionModel : model)
                .timeout(Duration.ofSeconds(60))
                .maxRetries(0)
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder().options(options).build();
        return ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    /** Builds a request-scoped client for the user's selected provider/model. */
    public ChatClient create(ModelProfileService.SelectedModel selection) {
        if (selection == null || selection.apiKey() == null || selection.apiKey().isBlank()) {
            throw new IllegalStateException("请先在模型设置中配置可用的 API Key");
        }
        ModelProviderRegistry.ProviderDescriptor descriptor = providers.resolve(
                selection.provider(), selection.capability(), selection.model());
        String selectedBaseUrl = descriptor.defaultBaseUrl();
        if (selectedBaseUrl == null || selectedBaseUrl.isBlank()) selectedBaseUrl = baseUrl;
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .apiKey(selection.apiKey())
                .baseUrl(selectedBaseUrl)
                .model(selection.model())
                .timeout(Duration.ofSeconds(60))
                .maxRetries(0)
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder().options(options).build();
        return ChatClient.builder(chatModel)
                .defaultSystem(systemPrompt)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }
}
