package com.bbb.exercise.agentdemo.chatservice.chat;

import com.bbb.exercise.agentdemo.chatservice.conversation.ConversationIntent;
import com.bbb.exercise.agentdemo.runtime.identity.ChatIdentityResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Authenticated intent lookup only; this endpoint cannot create a media job. */
@RestController
@RequestMapping("/api/chat/image-intent")
@RequiredArgsConstructor
public class ImageIntentController {
    private final ChatIdentityResolver identityResolver;
    private final ModelConversationService classifier;

    public record Request(String question) {}
    public record Result(ConversationIntent intent) {}

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Result> classify(@RequestBody(required = false) Request request, ServerWebExchange exchange) {
        String question = request == null ? null : request.question();
        String invalid = ChatService.validateQuestion(question, false);
        if (invalid != null) return Mono.error(new IllegalArgumentException(invalid));
        return identityResolver.resolveRequired(exchange)
                .flatMap(identity -> classifier.classifyImageQuestion(identity, question))
                .map(Result::new);
    }
}
