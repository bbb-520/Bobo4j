package com.bbb.exercise.agentdemo1_0.model;

import com.bbb.exercise.agentdemo1_0.identity.ChatIdentityResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/settings/models")
@RequiredArgsConstructor
public class ModelProfileController {
    private final ChatIdentityResolver identities;
    private final ModelProfileService profiles;
    private final ModelProviderRegistry registry;

    @GetMapping("/catalog")
    public ResponseEntity<Map<String, Object>> catalog() {
        List<Map<String, Object>> providers = registry.providers().stream().map(provider -> {
            ModelProviderRegistry.ProviderDescriptor descriptor = registry.resolve(provider,
                    descriptorFirstCapability(provider), "catalog");
            return Map.<String, Object>of("provider", provider.name(),
                    "capabilities", descriptor.capabilities(),
                    "openAiCompatible", descriptor.openAiCompatible(),
                    "defaultBaseUrl", descriptor.defaultBaseUrl());
        }).toList();
        return ResponseEntity.ok(Map.of("providers", providers,
                "capabilities", Arrays.stream(ModelCapability.values()).toList()));
    }

    @GetMapping
    public Mono<List<ModelProfile>> list(ServerWebExchange exchange) {
        return identities.resolveRequired(exchange)
                .map(profiles::list)
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping
    public Mono<ModelProfile> save(@RequestBody ModelProfileService.SaveRequest request,
                                   ServerWebExchange exchange) {
        return identities.resolveRequired(exchange)
                .map(identity -> profiles.save(identity, request))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{capability}")
    public Mono<Void> clear(@PathVariable String capability, ServerWebExchange exchange) {
        return identities.resolveRequired(exchange)
                .doOnNext(identity -> profiles.clear(identity, ModelCapability.parse(capability)))
                .then()
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static ModelCapability descriptorFirstCapability(ModelProvider provider) {
        return switch (provider) {
            case GPT, QWEN, HY -> ModelCapability.CHAT;
            case GEMINI, GLM -> ModelCapability.VISION;
        };
    }
}
