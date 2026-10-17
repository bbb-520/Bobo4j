package com.bbb.exercise.agentdemo.chatservice.chat;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.api.model.ModelContracts.Completion;
import com.bbb.exercise.agentdemo.chatservice.conversation.ConversationIntent;
import com.bbb.exercise.agentdemo.runtime.client.ModelCallClient;
import com.bbb.exercise.agentdemo.runtime.identity.ChatIdentityResolver;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ImageIntentControllerTest {
    final ChatIdentity owner = new ChatIdentity("tenant", "user", true);
    final ChatIdentityResolver identities = mock(ChatIdentityResolver.class);
    final ModelCallClient calls = mock(ModelCallClient.class);
    final ModelConversationService classifier = new ModelConversationService(null, null, null, null, calls, null);
    final ImageIntentController controller = new ImageIntentController(identities, classifier);
    final org.springframework.web.server.ServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/chat/image-intent").build());

    @Test void authenticatedRequestUsesTheExistingClassifierWithoutVisionOrGeneration() {
        when(identities.resolveRequired(exchange)).thenReturn(Mono.just(owner));
        when(calls.complete(eq(owner), anyString(), eq("CLASSIFY"), eq(""), eq("这张图帮我换成水彩"), eq(false)))
                .thenReturn(new Completion("GENERATE", 5, 1, "classifier", false));
        assertThat(controller.classify(new ImageIntentController.Request("这张图帮我换成水彩"), exchange).block().intent())
                .isEqualTo(ConversationIntent.GENERATE);
        verify(calls, times(1)).complete(eq(owner), anyString(), eq("CLASSIFY"), eq(""), anyString(), eq(false));
        verifyNoMoreInteractions(calls);
    }

    @Test void unauthenticatedRequestCannotSpendTokens() {
        when(identities.resolveRequired(exchange)).thenReturn(Mono.error(new IllegalStateException("auth required")));
        assertThatThrownBy(() -> controller.classify(new ImageIntentController.Request("改成水彩"), exchange).block())
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(calls);
    }

    @Test void invalidInputCannotSpendTokens() {
        for (String question : new String[] {"", " ", "猫".repeat(4001)}) {
            assertThatThrownBy(() -> controller.classify(new ImageIntentController.Request(question), exchange).block())
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> controller.classify(null, exchange).block()).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(calls);
    }

    @Test void timeoutAndMalformedResultsDoNotAuthorizeAnotherPaidCall() {
        when(identities.resolveRequired(exchange)).thenReturn(Mono.just(owner));
        when(calls.complete(eq(owner), anyString(), eq("CLASSIFY"), eq(""), anyString(), eq(false)))
                .thenReturn(new Completion("GENERATE please", 5, 1, "classifier", false))
                .thenThrow(new ModelCallClient.ModelCallException("TIMEOUT", false, 0));
        for (int i=0; i<2; i++) assertThat(controller.classify(new ImageIntentController.Request("改成水彩"), exchange).block().intent())
                .isEqualTo(ConversationIntent.WAIT_FOR_INSTRUCTION);
        verify(calls, times(2)).complete(eq(owner), anyString(), eq("CLASSIFY"), eq(""), anyString(), eq(false));
        verifyNoMoreInteractions(calls);
    }

    @Test void publicJsonRouteReturnsTheClassifierVerdict() {
        when(identities.resolveRequired(any())).thenReturn(Mono.just(owner));
        when(calls.complete(eq(owner), anyString(), eq("CLASSIFY"), eq(""), anyString(), eq(false)))
                .thenReturn(new Completion("DESCRIBE", 5, 1, "classifier", false));
        WebTestClient.bindToController(controller).controllerAdvice(new ChatExceptionHandler()).build()
                .post().uri("/api/chat/image-intent").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("question", "这张图是什么风格"))
                .exchange().expectStatus().isOk().expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody().jsonPath("$.intent").isEqualTo("DESCRIBE");
    }

    @Test void publicRouteReturns401WithoutCallingTheClassifier() {
        when(identities.resolveRequired(any())).thenReturn(Mono.error(new IllegalStateException("auth required")));
        WebTestClient.bindToController(controller).controllerAdvice(new ChatExceptionHandler()).build()
                .post().uri("/api/chat/image-intent").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("question", "改成水彩"))
                .exchange().expectStatus().isUnauthorized();
        verifyNoInteractions(calls);
    }

    @Test void editVerdictPreservesReferenceSemanticsInThePublicResult() {
        when(identities.resolveRequired(exchange)).thenReturn(Mono.just(owner));
        when(calls.complete(eq(owner), anyString(), eq("CLASSIFY"), eq(""), anyString(), eq(false)))
                .thenReturn(new Completion("REMIX", 5, 1, "classifier", false));
        assertThat(controller.classify(new ImageIntentController.Request("改成水彩"), exchange).block().intent())
                .isEqualTo(ConversationIntent.REMIX);
    }
}
