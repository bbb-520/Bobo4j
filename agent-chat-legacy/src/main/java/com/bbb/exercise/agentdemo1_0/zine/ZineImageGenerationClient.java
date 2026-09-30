package com.bbb.exercise.agentdemo1_0.zine;

import reactor.core.publisher.Mono;

/** Provider port for image-to-image generation; the image reference may be a data URL or a short-lived HTTPS URL. */
public interface ZineImageGenerationClient {

    Mono<ZineImageResult> generate(String imageDataUrl, String prompt, String apiKey);

    record ZineImageResult(String imageUrl, String providerRequestId) {
    }
}
