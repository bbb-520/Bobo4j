package com.bbb.exercise.agentdemo1_0.chat;

import com.bbb.exercise.agentdemo1_0.dto.ChatRequest;
import com.bbb.exercise.agentdemo1_0.identity.ChatIdentityResolver;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

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
}
