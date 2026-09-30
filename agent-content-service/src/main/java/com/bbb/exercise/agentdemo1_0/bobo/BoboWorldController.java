package com.bbb.exercise.agentdemo1_0.bobo;

import com.bbb.exercise.agentdemo1_0.identity.ChatIdentityResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/** Bobo's World 的公开读取和登录用户作品管理 API。 */
@RestController
@RequestMapping("/api/bobo")
@RequiredArgsConstructor
public class BoboWorldController {
    private final ChatIdentityResolver identityResolver;
    private final BoboWorldService boboWorld;

    @PostMapping("/items")
    public Mono<ResponseEntity<BoboWorldService.PublishResponse>> publish(
            @RequestBody(required = false) BoboWorldService.PublishRequest request,
            ServerWebExchange exchange) {
        return identityResolver.resolveRequired(exchange)
                .flatMap(identity -> Mono.fromCallable(() ->
                        ResponseEntity.status(HttpStatus.CREATED).body(boboWorld.publish(identity, request))))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/world")
    public Mono<ResponseEntity<BoboWorldService.WorldPage>> world(
            @RequestParam(defaultValue = "24") int limit,
            @RequestParam(required = false) String cursor) {
        return Mono.fromCallable(() -> ResponseEntity.ok(boboWorld.world(limit, cursor)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/items/mine")
    public Mono<ResponseEntity<BoboWorldService.MinePage>> mine(
            @RequestParam(defaultValue = "24") int limit,
            @RequestParam(required = false) String cursor,
            ServerWebExchange exchange) {
        return identityResolver.resolveRequired(exchange)
                .flatMap(identity -> Mono.fromCallable(() -> ResponseEntity.ok(boboWorld.mine(identity, limit, cursor))))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/items/{itemId}")
    public Mono<ResponseEntity<BoboWorldService.MineItem>> get(
            @PathVariable String itemId, ServerWebExchange exchange) {
        return identityResolver.resolve(exchange)
                .flatMap(identity -> Mono.fromCallable(() -> ResponseEntity.ok(boboWorld.get(identity, itemId))))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PatchMapping("/items/{itemId}")
    public Mono<ResponseEntity<BoboWorldService.MineItem>> patch(
            @PathVariable String itemId,
            @RequestBody(required = false) Map<String, Object> body,
            ServerWebExchange exchange) {
        BoboWorldService.PatchRequest patch = parsePatch(body);
        return identityResolver.resolveRequired(exchange)
                .flatMap(identity -> Mono.fromCallable(() -> ResponseEntity.ok(boboWorld.patch(identity, itemId, patch))))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/items/{itemId}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable String itemId, ServerWebExchange exchange) {
        return identityResolver.resolveRequired(exchange)
                .flatMap(identity -> Mono.fromCallable(() -> {
                    boboWorld.delete(identity, itemId);
                    return ResponseEntity.noContent().<Void>build();
                }))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static BoboWorldService.PatchRequest parsePatch(Map<String, Object> body) {
        if (body == null || !(body.get("version") instanceof Number versionNumber)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "version 为必填数字");
        }
        int version = versionNumber.intValue();
        if (version < 1 || versionNumber.doubleValue() != version) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "version 无效");
        }
        boolean captionProvided = body.containsKey("caption");
        Object rawCaption = body.get("caption");
        if (captionProvided && rawCaption != null && !(rawCaption instanceof String)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "caption 必须是字符串或 null");
        }
        Object rawAnonymous = body.get("anonymous");
        if (rawAnonymous != null && !(rawAnonymous instanceof Boolean)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "anonymous 必须是布尔值");
        }
        Object rawVisibility = body.get("visibility");
        if (rawVisibility != null && !(rawVisibility instanceof String)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "visibility 必须是字符串");
        }
        return new BoboWorldService.PatchRequest(captionProvided, (String) rawCaption,
                (Boolean) rawAnonymous, (String) rawVisibility, version);
    }
}
