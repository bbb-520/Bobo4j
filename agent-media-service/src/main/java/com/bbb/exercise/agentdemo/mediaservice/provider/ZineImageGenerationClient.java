package com.bbb.exercise.agentdemo.mediaservice.provider;

import reactor.core.publisher.Mono;

/** Zine 图片生成客户端契约，统一图片输入、提示词及供应商生成结果。 */
public interface ZineImageGenerationClient {
    Mono<ZineImageResult> generate(String imageDataUrl, String prompt, String apiKey);

    /** 图片供应商的生成回执，包含图片地址、请求号和输入输出用量。 */
    record ZineImageResult(
            String imageUrl, String providerRequestId, Long inputTokens, Long outputTokens) {
        public ZineImageResult(String url, String id) {
            this(url, id, null, null);
        }
    }
}
