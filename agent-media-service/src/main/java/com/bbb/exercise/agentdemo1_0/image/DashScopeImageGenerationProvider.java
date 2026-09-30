package com.bbb.exercise.agentdemo1_0.image;

import com.bbb.exercise.agentdemo1_0.model.ModelProvider;
import com.bbb.exercise.agentdemo1_0.zine.DashScopeImageGenerationClient;
import com.bbb.exercise.agentdemo1_0.zine.ZineImageGenerationClient;
import reactor.core.publisher.Mono;

final class DashScopeImageGenerationProvider implements ImageGenerationProvider {
    private final ZineImageGenerationClient client;
    public DashScopeImageGenerationProvider(ZineImageGenerationClient client) { this.client = client; }
    @Override public ModelProvider provider() { return ModelProvider.QWEN; }
    @Override public Mono<Result> generate(Request request) {
        if (client == null) return Mono.error(new IllegalStateException("DashScope 图片适配器未配置"));
        return client.generate(request.imageReference(), request.prompt(), request.apiKey())
                .map(result -> new Result(result.imageUrl(), result.providerRequestId(), "DashScope image remix"));
    }
}
