package com.bbb.exercise.agentdemo.chatservice.chat;

import com.bbb.exercise.agentdemo.api.dto.ChatRequest;
import com.bbb.exercise.agentdemo.runtime.identity.ChatIdentityResolver;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ChatControllerSseContractTest {
    @Test
    void invalidChatRequestStillNegotiatesEventStream() {
        ChatController controller = new ChatController(mock(ChatService.class), mock(ChatIdentityResolver.class));
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/chat").build());

        var response = controller.chat(new ChatRequest(), exchange).block();

        assertThat(response).isNotNull();
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM);
    }

    @Test
    void keepsTheDocumentedAndLegacyChatRoutesAvailable() throws NoSuchMethodException {
        var mapping = ChatController.class.getDeclaredMethod("chat", ChatRequest.class,
                        org.springframework.web.server.ServerWebExchange.class)
                .getAnnotation(PostMapping.class);

        assertThat(Arrays.asList(mapping.path())).contains("", "/completions");
    }
}
