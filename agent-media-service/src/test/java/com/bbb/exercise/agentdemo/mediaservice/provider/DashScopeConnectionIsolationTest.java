package com.bbb.exercise.agentdemo.mediaservice.provider;

import com.bbb.exercise.agentdemo.mediaservice.config.ZineProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import static org.assertj.core.api.Assertions.assertThat;

class DashScopeConnectionIsolationTest {
    @Test void paidImagePostsDoNotReuseAnIdleProviderConnection() throws Exception {
        var peers = ConcurrentHashMap.<Integer>newKeySet();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/generate", exchange -> {
            peers.add(exchange.getRemoteAddress().getPort());
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"request_id\":\"receipt\",\"output\":{\"results\":[{\"url\":\"https://example.test/image.png\"}]}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var properties = new ZineProperties();
            properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.setEndpoint("/generate");
            var client = new DashScopeImageGenerationClient(properties);
            for (int i=0; i<2; i++) {
                assertThat(client.generate(null, "draw a cat", "test-key", "qwen-image")
                        .block(Duration.ofSeconds(10))).isNotNull();
            }
            assertThat(peers).as("Each paid POST must establish its own connection").hasSize(2);
        } finally { server.stop(0); }
    }
}
