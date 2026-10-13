package com.bbb.exercise.agentdemo.ragservice.gallery;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.client.ChatClient;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GalleryConfigurationTest {
    private final ApplicationContextRunner runner=new ApplicationContextRunner().withUserConfiguration(GalleryConfiguration.class);
    @Test void disabledByDefaultCreatesNoGalleryOrModelBeans() {
        runner.run(context->{assertThat(context).doesNotHaveBean(PromptGalleryService.class);assertThat(context).doesNotHaveBean(GalleryModels.class);});
    }
    @Test void enabledWithoutKeyStartsAndProvidesActionableErrorOnlyOnModelUse() {
        runner.withPropertyValues("app.prompt-gallery.enabled=true").run(context->{
            assertThat(context).hasSingleBean(PromptGalleryService.class);
            assertThatIllegalStateException().isThrownBy(()->context.getBean(GalleryModels.class).embeddingModel()).withMessageContaining("OPENAI_API_KEY");
        });
    }
    @Test void reusesPlatformModelsWithoutReadingFallbackCredentials() {
        var embedding=mock(EmbeddingModel.class);var chat=mock(ChatModel.class);
        runner.withPropertyValues("app.prompt-gallery.enabled=true","app.prompt-gallery.chat-enabled=true")
                .withBean("platformEmbedding",EmbeddingModel.class,()->embedding)
                .withBean("platformChat",ChatModel.class,()->chat).run(context->{
                    assertThat(context.getBean(GalleryModels.class).embeddingModel()).isSameAs(embedding);
                    assertThat(context.getBean(GalleryModels.class).chatClient()).isInstanceOf(ChatClient.class);
                    verifyNoInteractions(embedding,chat);
                });
    }
}
