package com.bbb.exercise.agentdemo.mediaservice.image;

import com.bbb.exercise.agentdemo.api.model.ModelProvider;
import reactor.core.publisher.Mono;

/** Provider port for asynchronous image generation/remix jobs. */
public interface ImageGenerationProvider {
    ModelProvider provider();
    Mono<Result> generate(Request request);

    record Request(String imageReference, String prompt, String apiKey, String model) {
        @Override public String toString() {return "ImageRequest[model="+model+"]";}
    }
    record Result(String imageUrl, String providerRequestId, String rationale, Long inputTokens, Long outputTokens) {
        public Result(String imageUrl,String providerRequestId,String rationale) {this(imageUrl,providerRequestId,rationale,null,null);}
    }
}
