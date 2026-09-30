package com.bbb.exercise.agentdemo1_0.zine;

import com.bbb.exercise.agentdemo1_0.config.ZineProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.util.List;
import java.util.Map;

/**
 * Native DashScope multimodal image-edit adapter.
 *
 * <p>The provider accepts either a data URL (legacy single-request endpoint) or
 * a short-lived private OSS URL (the background-job path).
 */
@Slf4j
@Component
public class DashScopeImageGenerationClient implements ZineImageGenerationClient {

    private final WebClient webClient;
    private final ZineProperties properties;

    public DashScopeImageGenerationClient(WebClient.Builder webClientBuilder, ZineProperties properties) {
        HttpClient httpClient = HttpClient.create().responseTimeout(properties.getTimeout());
        this.webClient = webClientBuilder.clone()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .baseUrl(properties.getBaseUrl())
                .build();
        this.properties = properties;
    }

    @Override
    public Mono<ZineImageResult> generate(String imageDataUrl, String prompt, String apiKey) {
        if (!properties.isEnabled()) {
            return Mono.error(new IllegalStateException("图片生成服务未启用，请设置 app.zine.enabled=true"));
        }
        if (apiKey == null || apiKey.isBlank()) {
            return Mono.error(new IllegalStateException("请先在用户页配置阿里云 API Key"));
        }

        Map<String, Object> request = Map.of(
                "model", properties.getModel(),
                "input", Map.of(
                        "messages", List.of(Map.of(
                                "role", "user",
                                "content", List.of(
                                        Map.of("image", imageDataUrl),
                                        Map.of("text", prompt))))),
                "parameters", Map.of(
                        "prompt_extend", properties.isPromptExtend(),
                        "watermark", properties.isWatermark(),
                        "n", 1,
                        "size", properties.getSize()));

        return webClient.post()
                .uri(properties.getEndpoint())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + apiKey)
                .bodyValue(request)
                .retrieve()
                .onStatus(status -> status.isError(), response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("无响应正文")
                        .map(body -> new ZineProviderException("图片生成服务返回 " + response.statusCode().value()
                                + "：" + shorten(body))))
                .bodyToMono(JsonNode.class)
                .map(this::parseResult)
                .doOnError(error -> log.warn("[zine] image provider failed: {}", error.getMessage()));
    }

    private ZineImageResult parseResult(JsonNode root) {
        JsonNode image = findImage(root.at("/output/choices/0/message/content"));
        if (image.isMissingNode()) {
            image = findImage(root.at("/output/results"));
        }
        if (image.isMissingNode() || image.asText().isBlank()) {
            throw new ZineProviderException("图片生成服务未返回图片地址");
        }
        JsonNode requestId = root.get("request_id");
        return new ZineImageResult(image.asText(), requestId == null ? null : requestId.asText());
    }

    private static JsonNode findImage(JsonNode candidates) {
        if (candidates.isArray()) {
            for (JsonNode candidate : candidates) {
                JsonNode image = candidate.get("image");
                if (image != null && !image.asText().isBlank()) {
                    return image;
                }
                JsonNode url = candidate.get("url");
                if (url != null && !url.asText().isBlank()) {
                    return url;
                }
            }
        }
        return MissingNode.getInstance();
    }

    private static String shorten(String value) {
        String compact = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return compact.length() <= 500 ? compact : compact.substring(0, 500) + "…";
    }
}
