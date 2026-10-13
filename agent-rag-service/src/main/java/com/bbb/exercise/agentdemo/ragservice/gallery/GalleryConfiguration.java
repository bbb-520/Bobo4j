package com.bbb.exercise.agentdemo.ragservice.gallery;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(prefix="app.prompt-gallery",name="enabled",havingValue="true")
@EnableConfigurationProperties(GalleryProperties.class)
public class GalleryConfiguration {
    @Bean GalleryModels galleryModels(GalleryProperties properties,ObjectProvider<EmbeddingModel> embeddings,
                                      ObjectProvider<ChatModel> models,ObjectProvider<ChatClient> clients) {
        return new GalleryModels(properties,embeddings,models,clients);
    }
    @Bean(name="promptGalleryVectorStore",destroyMethod="close")
    MilvusGalleryVectorStore promptGalleryVectorStore(GalleryProperties properties,GalleryModels models) {
        return new MilvusGalleryVectorStore(properties,models::embeddingModel);
    }
    @Bean GalleryMarkdownParser galleryMarkdownParser() { return new GalleryMarkdownParser(); }
    @Bean PromptGalleryService promptGalleryService(@Qualifier("promptGalleryVectorStore") MilvusGalleryVectorStore store,
                                                   GalleryMarkdownParser parser,GalleryProperties properties) {
        return new PromptGalleryService(store,parser,properties);
    }
    @Bean GalleryPromptAssistant galleryPromptAssistant(PromptGalleryService gallery,GalleryModels models) {
        return new GalleryPromptAssistant(gallery,models::chatClient);
    }
    @Bean GalleryUsageGate galleryUsageGate(GalleryProperties properties) { return new GalleryUsageGate(properties); }
}
