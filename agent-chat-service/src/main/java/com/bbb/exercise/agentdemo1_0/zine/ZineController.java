package com.bbb.exercise.agentdemo1_0.zine;

import com.bbb.exercise.agentdemo1_0.auth.UserApiKeyService;
import com.bbb.exercise.agentdemo1_0.identity.ChatIdentityResolver;
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
    private final UserApiKeyService userKeys;

    @PostMapping(value = "/generate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<ZineGenerationService.ZineGenerationResponse>> generate(
            @RequestPart("image") FilePart image,
            @RequestPart(name = "mode", required = false) String mode,
            @RequestPart(name = "language", required = false) String language,
            @RequestPart(name = "text", required = false) String text,
            @RequestPart(name = "guidance", required = false) String guidance,
            ServerWebExchange exchange) {
        String contentType = image.headers().getContentType() == null
                ? null : image.headers().getContentType().toString();

        return identities.resolveRequired(exchange)
                .flatMap(identity -> Mono.fromCallable(() -> {
                    String key = userKeys.get(identity).qwenApiKey();
                    if (key == null || key.isBlank()) throw new IllegalStateException("请先在用户页配置阿里云 API Key");
                    return key;
                })
                        .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic()))
                .flatMap(apiKey -> DataBufferUtils.join(image.content())
                        .map(this::toBytes)
                        .flatMap(bytes -> generationService.generate(bytes, contentType, mode, language, text, guidance, apiKey)))
                .map(ResponseEntity::ok);
    }

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
