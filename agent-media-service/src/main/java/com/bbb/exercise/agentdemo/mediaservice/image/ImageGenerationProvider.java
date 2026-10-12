package com.bbb.exercise.agentdemo.mediaservice.image;

import com.bbb.exercise.agentdemo.api.model.ModelProvider;
import reactor.core.publisher.Mono;

/** Provider port for asynchronous image generation/remix jobs. */
public interface ImageGenerationProvider {
    ModelProvider provider();

    Mono<Result> generate(Request request);

    /** 图片生成的供应商输入，携带素材、提示词及实际选用的模型凭据。 */
    record Request(String imageReference, String prompt, String apiKey, String model) {
        @Override
        public String toString() {
            return "ImageRequest[model=" + model + "]";
        }
    }

    /** 图片生成的统一供应商回执，包含图片地址、生成依据及用量。 */
    record Result(
            String imageUrl,
            String providerRequestId,
            String rationale,
            Long inputTokens,
            Long outputTokens) {
        public Result(String imageUrl, String providerRequestId, String rationale) {
            this(imageUrl, providerRequestId, rationale, null, null);
        }
    }
}
