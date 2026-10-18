package com.bbb.exercise.agentdemo.contentservice.zine;

import com.bbb.exercise.agentdemo.runtime.identity.ChatIdentityResolver;
import com.bbb.exercise.agentdemo.contentservice.ZineCompositionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/zine")
@RequiredArgsConstructor
public class ZineController {
    private final ZineGenerationService generationService;
    private final ChatIdentityResolver identities;
    private final ZineCompositionProperties properties;
    private final ZineResultService results;

    @PostMapping(value="/generate", consumes=MediaType.MULTIPART_FORM_DATA_VALUE, produces=MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<ZineResultService.Result>> generate(
            @RequestPart("image") FilePart image,
            @RequestPart(name="requestId", required=false) String requestId,
            @RequestPart(name="mode", required=false) String mode,
            @RequestPart(name="language", required=false) String language,
            @RequestPart(name="text", required=false) String text,
            @RequestPart(name="guidance", required=false) String guidance,
            ServerWebExchange exchange) {
        String id = stableRequestId(requestId, exchange.getRequest().getHeaders().getFirst("Idempotency-Key"));
        String type = image.headers().getContentType() == null ? null : image.headers().getContentType().toString();
        return identities.resolveRequired(exchange).flatMap(identity ->
                DataBufferUtils.join(image.content(), properties.getMaxUploadBytes()).map(this::toBytes)
                        .flatMap(bytes -> results.generate(identity, generationService.compose(id,bytes,type,mode,language,text,guidance))))
                .map(ResponseEntity::ok);
    }
    static String stableRequestId(String part, String header) {
        if (part != null && header != null && !part.equals(header))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"requestId 与 Idempotency-Key 必须一致");
        String id = part != null ? part : header;
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请提供稳定的小写 UUID requestId 或 Idempotency-Key");
        return id;
    }
    @GetMapping("/generations")
    public Mono<java.util.List<String>> list(ServerWebExchange exchange) {
        return identities.resolveRequired(exchange).flatMap(results::list);
    }
    @GetMapping("/generations/{id}")
    public Mono<ZineResultService.Result> get(@PathVariable String id, ServerWebExchange exchange) {
        return identities.resolveRequired(exchange).flatMap(identity -> results.get(identity,id));
    }
    private byte[] toBytes(DataBuffer buffer) {
        try { byte[] bytes = new byte[buffer.readableByteCount()]; buffer.read(bytes); return bytes; }
        finally { DataBufferUtils.release(buffer); }
    }
}
