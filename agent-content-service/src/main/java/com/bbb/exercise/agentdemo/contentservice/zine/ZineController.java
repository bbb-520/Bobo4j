package com.bbb.exercise.agentdemo.contentservice.zine;

import com.bbb.exercise.agentdemo.runtime.client.AuthCredentialClient;
import com.bbb.exercise.agentdemo.runtime.identity.ChatIdentityResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import reactor.core.publisher.Mono;

/** Upload-and-generate API for the image editing app. */
@RestController
@RequestMapping("/api/zine")
@RequiredArgsConstructor
public class ZineController {

    private final ZineGenerationService generationService;
    private final ChatIdentityResolver identities;
    private final AuthCredentialClient userKeys;
    private final com.bbb.exercise.agentdemo.runtime.client.AuthModelClient models;
    private final com.bbb.exercise.agentdemo.runtime.client.BillingClient billing;
    private final com.bbb.exercise.agentdemo.contentservice.ZineProperties properties;
    private final ZineResultService results;

    @PostMapping(value = "/generate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<ZineResultService.Result>> generate(
            @RequestPart("image") FilePart image,
            @RequestPart(name = "mode", required = false) String mode,
            @RequestPart(name = "language", required = false) String language,
            @RequestPart(name = "text", required = false) String text,
            @RequestPart(name = "guidance", required = false) String guidance,
            ServerWebExchange exchange) {
        String contentType = image.headers().getContentType() == null
                ? null : image.headers().getContentType().toString();

        return identities.resolveRequired(exchange)
                .flatMap(identity -> Mono.fromCallable(() -> models.resolve(identity,com.bbb.exercise.agentdemo.api.model.ModelCapability.IMAGE))
                        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
                        .flatMap(selected -> DataBufferUtils.join(image.content(),Math.toIntExact(properties.getMaxUploadBytes()))
                                .map(this::toBytes).flatMap(bytes -> {
                                    if(!selected.model().equals(properties.getModel())) return Mono.error(new IllegalStateException("图片模型与平台配置不一致"));
                                    String request="zine:"+java.util.UUID.randomUUID();
                                    // Validate source/prompt before reserving; defer the actual HTTP request until dispatch.
                                    var provider=generationService.generate(bytes,contentType,mode,language,text,guidance,selected.apiKey());
                                    return results.generate(identity,request,selected.model(),provider);
                                })))
                .map(ResponseEntity::ok);
    }
    @org.springframework.web.bind.annotation.GetMapping("/generations")
    public Mono<java.util.List<String>> list(ServerWebExchange exchange) {return identities.resolveRequired(exchange).flatMap(results::list);}
    @org.springframework.web.bind.annotation.GetMapping("/generations/{id}")
    public Mono<ZineResultService.Result> get(@org.springframework.web.bind.annotation.PathVariable String id,ServerWebExchange exchange) {return identities.resolveRequired(exchange).flatMap(identity -> results.get(identity,id));}

    private byte[] toBytes(DataBuffer dataBuffer) {
        try {
            byte[] bytes = new byte[dataBuffer.readableByteCount()];
            dataBuffer.read(bytes);
            return bytes;
        } finally {
            DataBufferUtils.release(dataBuffer);
        }
    }
}
