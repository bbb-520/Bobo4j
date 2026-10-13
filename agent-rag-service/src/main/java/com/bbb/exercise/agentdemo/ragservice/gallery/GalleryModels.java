package com.bbb.exercise.agentdemo.ragservice.gallery;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.*;
import java.time.Duration;

/** Existing platform beans win. Fallback models are built only on an explicit administrator operation. */
public final class GalleryModels {
    private final GalleryProperties properties;
    private final ObjectProvider<EmbeddingModel> embeddings;
    private final ObjectProvider<ChatModel> chatModels;
    private volatile EmbeddingModel fallbackEmbedding;
    private volatile ChatClient fallbackChat;
    public GalleryModels(GalleryProperties properties,ObjectProvider<EmbeddingModel> embeddings,
                         ObjectProvider<ChatModel> chatModels) {
        this.properties=properties;this.embeddings=embeddings;this.chatModels=chatModels;
    }
    public synchronized EmbeddingModel embeddingModel() {
        var candidates=embeddings.orderedStream().limit(2).toList();
        if(candidates.size()>1) throw new GalleryNotReadyException("Provide exactly one platform EmbeddingModel bean for prompt gallery");
        if(!candidates.isEmpty()) return candidates.getFirst();
        if(fallbackEmbedding==null) {
            requireKey();
            fallbackEmbedding=OpenAiEmbeddingModel.builder().metadataMode(MetadataMode.NONE).options(OpenAiEmbeddingOptions.builder()
                    .apiKey(properties.getApiKey()).baseUrl(properties.getBaseUrl()).model(properties.getEmbeddingModel())
                    .dimensions(properties.getDimensions()).timeout(Duration.ofSeconds(60)).maxRetries(0).build()).build();
        }
        return fallbackEmbedding;
    }
    public synchronized ChatClient chatClient() {
        if(!properties.isChatEnabled()) throw new GalleryNotReadyException("Set PROMPT_GALLERY_CHAT_ENABLED=true to enable explicit administrator prompt adaptation");
        if(fallbackChat==null) {
            var models=chatModels.orderedStream().limit(2).toList();
            if(models.size()>1) throw new GalleryNotReadyException("Provide exactly one platform ChatModel bean for prompt gallery");
            ChatModel model;
            if(!models.isEmpty()) model=models.getFirst();
            else {
                requireKey();
                model=OpenAiChatModel.builder().options(OpenAiChatOptions.builder().apiKey(properties.getApiKey())
                        .baseUrl(properties.getBaseUrl()).model(properties.getChatModel()).maxCompletionTokens(1200)
                        .timeout(Duration.ofSeconds(90)).maxRetries(0).build()).build();
            }
            // A business ChatClient may carry tools, memory and execution advisors. Reuse only the model.
            fallbackChat=ChatClient.builder(model).build();
        }
        return fallbackChat;
    }
    private void requireKey() {
        if(properties.getApiKey()==null || properties.getApiKey().isBlank())
            throw new GalleryNotReadyException("Prompt gallery model is not configured: set OPENAI_API_KEY via application-gallery.yml (gallery profile), or provide a platform EmbeddingModel/ChatModel bean");
    }
}
