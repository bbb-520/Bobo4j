package com.bbb.exercise.agentdemo1_0.image;

import com.bbb.exercise.agentdemo1_0.model.ModelProvider;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

import java.util.Map;

/** Adapter for providers exposing a compatible JSON image endpoint. Endpoint/model are configurable per profile. */
final class OpenAiCompatibleImageGenerationProvider implements ImageGenerationProvider {
    private final ModelProvider provider;
    private final String baseUrl;
    private final WebClient webClient;

    OpenAiCompatibleImageGenerationProvider(ModelProvider provider, String baseUrl) {
        this.provider = provider;
        this.baseUrl = baseUrl;
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
    }
    @Override public ModelProvider provider() { return provider; }
    @Override public Mono<Result> generate(Request request) {
        if (request.apiKey() == null || request.apiKey().isBlank()) return Mono.error(new IllegalStateException("图片模型 API Key 未配置"));
        Map<String, Object> body = Map.of("model", request.model(), "prompt", request.prompt(), "image_url", request.imageReference(), "n", 1);
        return webClient.post().uri("/images/edits").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + request.apiKey()).bodyValue(body).retrieve()
                .bodyToMono(JsonNode.class).map(root -> {
                    JsonNode data = root.at("/data/0/url");
                    if (data.isMissingNode() || data.asText().isBlank()) throw new IllegalStateException(provider + " 图片接口未返回图片地址");
                    return new Result(data.asText(), root.path("id").asText(null), provider + " image remix");
                });
    }
}
