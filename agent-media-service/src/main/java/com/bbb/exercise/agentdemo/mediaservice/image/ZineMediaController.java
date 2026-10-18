package com.bbb.exercise.agentdemo.mediaservice.image;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.common.security.PrincipalKeyRing;
import com.bbb.exercise.agentdemo.runtime.client.InternalServiceClient.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/internal/media/zine")
public class ZineMediaController {
    private final ZineMediaJobService jobs;
    private final PrincipalKeyRing keys;
    public ZineMediaController(ZineMediaJobService jobs,
            @Value("${app.security.internal-principal-secrets:}") String secrets,
            @Value("${app.security.internal-principal-active-key-id:current}") String active) {
        this.jobs = jobs; this.keys = new PrincipalKeyRing(secrets, active);
    }
    @PostMapping("/generate")
    public Mono<ZineResult> generate(@RequestBody ZineRequest request,
            @RequestHeader("X-Internal-Principal") String token) {
        return jobs.generate(verify(token, "POST /internal/media/zine/generate"), request);
    }
    @GetMapping("/generations/{id}")
    public Mono<ZineResult> get(@PathVariable String id, @RequestHeader("X-Internal-Principal") String token) {
        var owner = verify(token, "GET /internal/media/zine/generations/" + id);
        return Mono.fromCallable(() -> jobs.get(owner,id)).subscribeOn(Schedulers.boundedElastic());
    }
    @GetMapping("/generations")
    public Mono<ZineRequests> list(@RequestHeader("X-Internal-Principal") String token) {
        var owner = verify(token, "GET /internal/media/zine/generations");
        return Mono.fromCallable(() -> new ZineRequests(jobs.list(owner))).subscribeOn(Schedulers.boundedElastic());
    }
    private ChatIdentity verify(String token, String operation) {
        try {
            var principal = keys.verify(token, "agent-media-service", operation);
            if (!"agent-content-service".equals(principal.service())) throw new IllegalArgumentException();
            return new ChatIdentity(principal.tenant(),principal.subject(),true);
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "内部身份签名无效");
        }
    }
}

/** Bounded JSON image transfer from Content; the client never sends provider credentials. */
@Component
class ZineMediaCodecConfiguration implements WebFluxConfigurer {
    @Override public void configureHttpMessageCodecs(ServerCodecConfigurer codecs) {
        codecs.defaultCodecs().maxInMemorySize(32 * 1024 * 1024);
    }
}
