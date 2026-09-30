package com.bbb.exercise.agentdemo1_0.image;

import com.bbb.exercise.agentdemo1_0.identity.ChatIdentityResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** OSS 直传策略与上传完成确认接口。 */
@RestController
@RequestMapping("/api/image-assets")
@RequiredArgsConstructor
public class ImageAssetController {
    private final ChatIdentityResolver identityResolver;
    private final ImageAssetService assets;

    @PostMapping("/upload-policy")
    public Mono<ResponseEntity<ImageAssetService.UploadPolicyView>> uploadPolicy(
            @RequestBody ImageAssetService.UploadPolicyRequest request, ServerWebExchange exchange) {
        return identityResolver.resolveRequired(exchange)
                .flatMap(identity -> Mono.fromCallable(() -> ResponseEntity.ok(assets.createPolicy(identity, request))))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{assetId}/complete")
    public Mono<ResponseEntity<ImageAssetService.AssetView>> complete(
            @PathVariable String assetId, ServerWebExchange exchange) {
        return identityResolver.resolveRequired(exchange)
                .flatMap(identity -> Mono.fromCallable(() -> ResponseEntity.ok(assets.complete(identity, assetId))))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
