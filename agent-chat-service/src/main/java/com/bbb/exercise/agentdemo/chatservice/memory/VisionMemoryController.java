package com.bbb.exercise.agentdemo.chatservice.memory;

import com.bbb.exercise.agentdemo.runtime.identity.ChatIdentityResolver;
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
    @org.springframework.web.bind.annotation.PostMapping("/reindex")
    public Mono<Map<String,Integer>> reindex(@RequestParam(defaultValue="4") int limit,ServerWebExchange exchange) {
        return identities.resolveRequired(exchange).flatMap(identity->Mono.fromCallable(()->Map.of("reindexed",memories.reindex(identity,limit))).subscribeOn(Schedulers.boundedElastic()));
    }

    // 1. 记忆上下文入口：基于当前认证身份返回与问题相关的视觉记忆。
    @org.springframework.web.bind.annotation.PostMapping("/context")
    public Mono<VisionMemoryService.SearchResult> context(
            @org.springframework.web.bind.annotation.RequestBody ContextQuery request, ServerWebExchange exchange) {
        if(request.requestId()==null||!request.requestId().matches("[A-Za-z0-9_.:-]{1,96}"))throw new IllegalArgumentException("视觉查询请求标识无效");
        return identities
                .resolveRequired(exchange)
                .map(identity -> memories.search(identity,request.query(),5,"vision:context:"+request.requestId(),request.tokenBudget()))
                .subscribeOn(Schedulers.boundedElastic());
    }
    public record ContextQuery(String requestId,String query,Long tokenBudget) {}
}
