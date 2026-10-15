package com.bbb.exercise.agentdemo.chatservice.memory;

import com.bbb.exercise.agentdemo.runtime.identity.ChatIdentityResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Read-only personal memory browser under the existing chat:use browser policy. */
@RestController
@RequestMapping("/api/settings/visual-memory")
public class VisualPromptController {
    private final ChatIdentityResolver identities;
    private final VisualPromptService prompts;

    public VisualPromptController(ChatIdentityResolver identities, VisualPromptService prompts) {
        this.identities = identities;
        this.prompts = prompts;
    }

    @GetMapping("/prompts")
    public Mono<VisualPromptService.Page> browse(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "24") int limit,
            @RequestParam(defaultValue = "") String cursor,
            ServerWebExchange exchange) {
        exchange.getResponse().getHeaders().set(HttpHeaders.CACHE_CONTROL, "private, no-store");
        return identities.resolveRequired(exchange)
                .flatMap(owner -> Mono.fromCallable(() -> prompts.browse(owner, q, limit, cursor))
                        .subscribeOn(Schedulers.boundedElastic()));
    }
}
