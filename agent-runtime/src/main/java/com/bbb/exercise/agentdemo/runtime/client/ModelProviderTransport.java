package com.bbb.exercise.agentdemo.runtime.client;

import com.bbb.exercise.agentdemo.api.model.*;
import com.bbb.exercise.agentdemo.api.model.ModelContracts.*;
import com.bbb.exercise.agentdemo.runtime.client.ModelCallClient.ModelCallException;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.MediaType;
import reactor.core.publisher.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Provider wire protocol and measured response parsing, independent of billing state. */
final class ModelProviderTransport {
    private final WebClient http;
    private final JsonMapper json;
    private final Duration first, idle;

    ModelProviderTransport(WebClient http, JsonMapper json, Duration first, Duration idle) {
        this.http = http;
        this.json = json;
        this.first = first;
        this.idle = idle;
    }

    record Result(Completion completion, String providerId) {}

    Result stream(AuthModelClient.SelectedModel model, String mode, String system, String user,
                  List<VisionImage> images, boolean backup, Duration timeout,
                  Consumer<GenerationEvent> events, AtomicBoolean primaryOutput,
                  boolean replace, long unknownTokens) {
        String trusted = com.bbb.exercise.agentdemo.api.model.ModelPrompts.forMode(mode, backup) + "\n" + system;
        Object content = user;

        if (!images.isEmpty()) {
            List<Object> blocks = new ArrayList<>();
            blocks.add(Map.of("type", "text", "text", user));

            for (VisionImage source : images) {
                Map<String, Object> image = new LinkedHashMap<>();
                image.put("url", source.url());

                if (model.provider() == ModelProvider.GPT) {
                    image.put("detail", "low");
                }

                blocks.add(Map.of("type", "image_url", "image_url", image));
            }

            content = blocks;
        }

        Map<String, Object> request = new LinkedHashMap<>();

        request.put("model", model.model());
        request.put(
                "messages",
                List.of(
                        Map.of("role", "system", "content", trusted),
                        Map.of("role", "user", "content", content)));
        request.put("stream", true);
        request.put("stream_options", Map.of("include_usage", true));
        request.put("max_tokens", "CLASSIFY".equals(mode) ? 16 : 2048);
        request.put("temperature", backup ? 0.2 : 0.3);

        if (model.provider() == ModelProvider.QWEN) {
            request.put("enable_thinking", false);
        }

        if (!images.isEmpty() && model.provider() == ModelProvider.QWEN) {
            request.put("vl_high_resolution_images", false);
        }

        if ("DECISION".equals(mode)) {
            request.put("response_format", Map.of("type", "json_object"));
        }

        var usage = new AtomicReference<JsonNode>();
        var providerId = new AtomicReference<String>();
        var firstBackup = new AtomicBoolean(true);
        Flux<JsonNode> frames =
                http.post()
                        .uri(endpoint(model) + "/chat/completions")
                        .headers(h -> h.setBearerAuth(model.apiKey()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(request)
                        .exchangeToFlux(
                                response -> {
                                    if (!response.statusCode().is2xxSuccessful()) {
                                        return response.releaseBody()
                                                .thenMany(
                                                        Flux.error(
                                                                httpError(
                                                                        response.statusCode()
                                                                                .value())));
                                    }

                                    if (response.headers()
                                            .contentType()
                                            .orElse(MediaType.APPLICATION_JSON)
                                            .isCompatibleWith(MediaType.TEXT_EVENT_STREAM)) {
                                        return response.bodyToFlux(String.class)
                                                .filter(
                                                        v ->
                                                                !v.isBlank()
                                                                        && !v.trim()
                                                                                .equals(
                                                                                        "[DONE]"))
                                                .map(json::readTree);
                                    }

                                    return response.bodyToMono(JsonNode.class).flux();
                                }).onErrorMap(ModelProviderTransport::providerTransportFailure);
        List<String> output =
                frames.doOnNext(
                                frame -> {
                                    if (frame.path("usage").isObject()) {
                                        usage.set(frame);
                                    }

                                    if (frame.path("id").isString()) {
                                        providerId.set(frame.path("id").asString());
                                    }
                                })
                        .map(ModelProviderTransport::text)
                        .filter(v -> !v.isEmpty())
                        .timeout(Mono.delay(first), v -> Mono.delay(idle))
                        .doOnNext(
                                text -> {
                                    if (!backup) {
                                        primaryOutput.set(true);
                                    }

                                    if ("CHAT".equals(mode) || "VISION".equals(mode)) {
                                        boolean replacement =
                                                backup
                                                        && firstBackup.getAndSet(false)
                                                        && replace;
                                        events.accept(
                                                new GenerationEvent(
                                                        replacement
                                                                ? "answer_replace"
                                                                : "answer_delta",
                                                        backup ? 2 : 1,
                                                        Map.of("text", text)));
                                    }
                                })
                        .collectList()
                        .timeout(timeout)
                        .block(timeout.plusSeconds(2));
        String answer = output == null ? "" : String.join("", output);

        if (answer.isBlank()) {
            throw new ModelCallException("EMPTY_OUTPUT", true, 0);
        }

        JsonNode measured = usage.get();

        if (measured == null) {
            throw protocol("MISSING_USAGE");
        }

        long input = token(measured, "prompt_tokens"),
                tokens = token(measured, "completion_tokens");
        var completed =
                new Completion(
                        answer,
                        input,
                        tokens,
                        model.model(),
                        backup,
                        Math.addExact(Math.addExact(input, tokens), unknownTokens),
                        unknownTokens,
                        model.provider().name());

        return new Result(completed, providerId.get());
    }

    JsonNode postJson(
            AuthModelClient.SelectedModel model, String path, Object request, Duration timeout) {
        return http.post()
                .uri(endpoint(model) + path)
                .headers(h -> h.setBearerAuth(model.apiKey()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchangeToMono(
                        response ->
                                response.statusCode().is2xxSuccessful()
                                        ? response.bodyToMono(JsonNode.class)
                                        : response.releaseBody()
                                                .then(
                                                        Mono.error(
                                                                httpError(
                                                                        response.statusCode()
                                                                                .value()))))
                .onErrorMap(ModelProviderTransport::providerTransportFailure)
                .timeout(timeout)
                .block(timeout.plusSeconds(2));
    }

    private static Throwable providerTransportFailure(Throwable error) {
        for(Throwable cause=error;cause!=null;cause=cause.getCause()) {
            if(cause instanceof java.net.ConnectException || cause instanceof java.net.UnknownHostException || cause instanceof java.net.http.HttpConnectTimeoutException || cause instanceof io.netty.channel.ConnectTimeoutException)return new ProviderConnectFailure();
        }
        return error;
    }

    private static ModelCallException httpError(int status) {
        if(Set.of(400,401,403,404,405,413,422,429).contains(status))return new DefiniteProviderFailure(status);
        return new ModelCallException(
                "PROVIDER_ERROR", status == 401 || status == 429 || status >= 500, status);
    }

    static ModelCallException protocol(String reason) {
        return new ModelCallException(reason, false, 409);
    }

    static String text(JsonNode node) {
        JsonNode c = node.path("choices").path(0);
        String delta = c.path("delta").path("content").asString(null);

        return delta != null ? delta : c.path("message").path("content").asString("");
    }

    static long token(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path("usage").path(field);

        if (value == null || !value.isIntegralNumber() || value.asLong(-1) < 0) {
            throw protocol("MISSING_USAGE");
        }

        return value.asLong();
    }

    static String endpoint(AuthModelClient.SelectedModel m) {
        String url = m.baseUrl();

        if (url == null || url.isBlank()) {
            url =
                    ModelProviderRegistry.defaultRegistry()
                            .resolve(m.provider(), m.capability(), m.model())
                            .defaultBaseUrl();
        }

        return url.replaceAll("/+$", "");
    }

    static class ProviderConnectFailure extends ModelCallException {
        ProviderConnectFailure(){super("PROVIDER_CONNECT_FAILED",true,503);}
    }

    static class DefiniteProviderFailure extends ModelCallException {
        DefiniteProviderFailure(int status){super("PROVIDER_REJECTED",status==401||status==429,status);}
    }

}
