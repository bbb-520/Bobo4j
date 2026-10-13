package com.bbb.exercise.agentdemo.ragservice.gallery;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.*;

/** Existing document gateway/security route; all paid operations are administrative and explicit. */
@RestController
@RequestMapping("/api/documents/prompt-gallery")
@ConditionalOnProperty(prefix="app.prompt-gallery",name="enabled",havingValue="true")
public final class GalleryController {
    private final PromptGalleryService gallery;
    private final GalleryPromptAssistant assistant;
    private final GalleryUsageGate gate;
    public GalleryController(PromptGalleryService gallery,GalleryPromptAssistant assistant,GalleryUsageGate gate) {
        this.gallery=gallery;this.assistant=assistant;this.gate=gate;
    }
    @PostMapping("/search")
    public Mono<List<PromptView>> search(@RequestBody SearchInput request,ServerWebExchange exchange) {
        GalleryAccess.modelOperator(exchange);
        return Mono.fromCallable(()->gate.execute(()->gallery.searchPrompts(request.query(),request.topK()==null?5:request.topK(),request.category()).stream()
                .map(d->new PromptView(d.getId(),d.getText(),d.getMetadata(),d.getScore())).toList())).subscribeOn(Schedulers.boundedElastic());
    }
    @PostMapping("/import")
    public Mono<PromptGalleryService.ImportResult> importSource(@RequestParam(defaultValue="true") boolean dryRun,ServerWebExchange exchange) {
        GalleryAccess.importer(exchange);
        return Mono.fromCallable(()->dryRun?gallery.importConfiguredSource(true):gate.execute(()->gallery.importConfiguredSource(false)))
                .subscribeOn(Schedulers.boundedElastic());
    }
    @PostMapping("/adapt")
    public Mono<GalleryPromptAssistant.Result> adapt(@RequestBody AdaptRequest request,ServerWebExchange exchange) {
        GalleryAccess.modelOperator(exchange);
        return Mono.fromCallable(()->gate.execute(()->assistant.adapt(request.request(),request.topK()==null?5:request.topK(),request.category(),request.rewrite())))
                .subscribeOn(Schedulers.boundedElastic());
    }
    public record PromptView(String id,String prompt,Map<String,Object> metadata,Double score) {}
    public record SearchInput(String query,Integer topK,String category) {}
    public record AdaptRequest(String request,Integer topK,String category,boolean rewrite) {}
}
