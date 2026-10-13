package com.bbb.exercise.agentdemo.ragservice.gallery;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.tool.ToolCallback;
import java.util.List;
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
    @Test void isolatesGalleryFromExistingClientDefaultToolsAndBusinessAdvisors() {
        var model=mock(ChatModel.class);var callback=mock(ToolCallback.class);var advisor=mock(CallAdvisor.class);
        when(advisor.getName()).thenReturn("business-advisor");
        when(model.getOptions()).thenReturn(org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build());
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("A forest.")))));
        var businessClient=ChatClient.builder(model).defaultToolCallbacks(callback).defaultAdvisors(advisor).build();
        runner.withPropertyValues("app.prompt-gallery.enabled=true","app.prompt-gallery.chat-enabled=true")
                .withBean("platformChat",ChatModel.class,()->model).withBean("businessClient",ChatClient.class,()->businessClient).run(context->{
                    var isolated=context.getBean(GalleryModels.class).chatClient();
                    assertThat(isolated).isNotSameAs(businessClient);
                    assertThat(isolated.prompt().user("paint").call().content()).isEqualTo("A forest.");
                    verify(advisor,never()).adviseCall(any(),any());verify(callback,never()).call(anyString());
                });
    }
}
