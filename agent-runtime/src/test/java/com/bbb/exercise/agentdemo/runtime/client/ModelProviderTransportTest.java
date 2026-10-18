package com.bbb.exercise.agentdemo.runtime.client;

import com.bbb.exercise.agentdemo.api.model.*;
import com.bbb.exercise.agentdemo.api.model.ModelContracts.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class ModelProviderTransportTest {
    @Test
    void visionFallbackPreservesRequestProtocolReplacementEventsAndMeasuredReceipt() throws Exception {
        var request = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] frames = ("data: {\"id\":\"provider-receipt\",\"choices\":[{\"delta\":{\"content\":\"first\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\" second\"}}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3}}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, frames.length);
            try (var body = exchange.getResponseBody()) { body.write(frames); }
        });
        server.start();
        try {
            var transport = transport();
            var events = new ArrayList<GenerationEvent>();
            var primary = new AtomicBoolean(false);
            var result = transport.stream(model(server), "VISION", "system", "question",
                    List.of(new VisionImage("asset", "https://example.com/image.png")), true,
                    Duration.ofSeconds(5), events::add, primary, true, 7);
            assertThat(result.completion().text()).isEqualTo("first second");
            assertThat(result.completion().inputTokens()).isEqualTo(12);
            assertThat(result.completion().outputTokens()).isEqualTo(3);
            assertThat(result.completion().reservedTokens()).isEqualTo(22);
            assertThat(result.completion().unknownTokens()).isEqualTo(7);
            assertThat(result.providerId()).isEqualTo("provider-receipt");
            assertThat(events).extracting(GenerationEvent::type).containsExactly("answer_replace", "answer_delta");
            assertThat(events).extracting(GenerationEvent::generationVersion).containsOnly(2);
            assertThat(primary.get()).isFalse();
            var payload = JsonMapper.builder().build().readTree(request.get());
            assertThat(payload.path("stream").asBoolean()).isTrue();
            assertThat(payload.path("messages").path(1).path("content").path(1)
                    .path("image_url").path("detail").asString()).isEqualTo("low");
        } finally { server.stop(0); }
    }

    @Test
    void rejectedHttpRequestNeverExposesTheProviderErrorBody() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/reject", exchange -> {
            byte[] body = "private-provider-credential".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            try (var stream = exchange.getResponseBody()) { stream.write(body); }
        });
        server.start();
        try {
            assertThatThrownBy(() -> transport().postJson(model(server), "/reject", Map.of(), Duration.ofSeconds(5)))
                    .isInstanceOf(ModelCallClient.ModelCallException.class)
                    .hasMessageNotContaining("private-provider-credential")
                    .satisfies(error -> assertThat(((ModelCallClient.ModelCallException) error).code())
                            .isEqualTo("PROVIDER_REJECTED"));
        } finally { server.stop(0); }
    }

    private static ModelProviderTransport transport() {
        return new ModelProviderTransport(WebClient.builder().build(), JsonMapper.builder().build(),
                Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    private static AuthModelClient.SelectedModel model(HttpServer server) {
        return new AuthModelClient.SelectedModel(ModelProvider.GPT, ModelCapability.VISION,
                "gpt-4.1", "test-only", "http://127.0.0.1:" + server.getAddress().getPort(), "platform");
    }
}
