package com.bbb.exercise.agentdemo1_0.image;

import com.bbb.exercise.agentdemo1_0.model.ModelProvider;
import reactor.core.publisher.Mono;

/** Provider port for asynchronous image generation/remix jobs. */
public interface ImageGenerationProvider {
    ModelProvider provider();
    Mono<Result> generate(Request request);

    record Request(String imageReference, String prompt, String apiKey, String model) {}
    record Result(String imageUrl, String providerRequestId, String rationale) {}
}
