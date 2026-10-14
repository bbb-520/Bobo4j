package com.bbb.exercise.agentdemo.chatservice.conversation.sync;

import com.bbb.exercise.agentdemo.runtime.identity.ChatIdentityResolver;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.List;

@RestController
@RequestMapping("/api/conversations")
public class ConversationSnapshotController {
    private final ConversationSnapshotService snapshots;
    private final ChatIdentityResolver identities;
    public ConversationSnapshotController(ConversationSnapshotService snapshots,ChatIdentityResolver identities) {this.snapshots=snapshots;this.identities=identities;}
    @GetMapping
    public Mono<ResponseEntity<List<ConversationSnapshotService.Index>>> list(@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="100") int limit,ServerWebExchange exchange) {
        return identities.resolveRequired(exchange).flatMap(owner->Mono.fromCallable(()->ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(snapshots.list(owner,offset,limit))).subscribeOn(Schedulers.boundedElastic()));
    }
    @GetMapping("/{id}")
    public Mono<ResponseEntity<ConversationSnapshotService.View>> get(@PathVariable String id,ServerWebExchange exchange) {
        return identities.resolveRequired(exchange).flatMap(owner->Mono.fromCallable(()->ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(snapshots.get(owner,id))).subscribeOn(Schedulers.boundedElastic()));
    }
    @GetMapping(params="recent=true")
    public Mono<ResponseEntity<ConversationSnapshotService.Page>> recent(@RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit,ServerWebExchange exchange) {
        return identities.resolveRequired(exchange).flatMap(owner->Mono.fromCallable(()->ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(snapshots.recent(owner,cursor,limit))).subscribeOn(Schedulers.boundedElastic()));
    }
    @PutMapping("/{id}")
    public Mono<ConversationSnapshotService.View> put(@PathVariable String id,@RequestBody ConversationSnapshotService.Write request,ServerWebExchange exchange) {
        return identities.resolveRequired(exchange).flatMap(owner->Mono.fromCallable(()->snapshots.put(owner,id,request)).subscribeOn(Schedulers.boundedElastic()));
    }
}
