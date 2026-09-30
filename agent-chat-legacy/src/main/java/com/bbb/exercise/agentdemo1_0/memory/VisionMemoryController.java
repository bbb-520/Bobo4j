package com.bbb.exercise.agentdemo1_0.memory;

import com.bbb.exercise.agentdemo1_0.identity.ChatIdentityResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/** Explicit retrieval endpoint; callers decide whether to forward context to a provider. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/settings/visual-memory")
public class VisionMemoryController {
    private final ChatIdentityResolver identities;
    private final VisionMemoryService memories;

    @GetMapping("/context")
    public Mono<Map<String, String>> context(@RequestParam String query,
                                             ServerWebExchange exchange) {
        return identities.resolveRequired(exchange)
                .map(identity -> Map.of("context", memories.augment(identity, query, 5)))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
