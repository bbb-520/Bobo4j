package com.bbb.exercise.agentdemo.mediaservice.provider;

import com.bbb.exercise.agentdemo.mediaservice.config.ZineProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class DashScopeTextToImageTest {
    @Test void textOnlyPayloadOmitsImageInsteadOfSendingNull() throws Exception {check(null,false);}
    @Test void referencePayloadPreservesImageAndText() throws Exception {check("https://example.test/source.png",true);}
    void check(String source,boolean image) throws Exception {
        var payload=new AtomicReference<JsonNode>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/generate",exchange->{
            payload.set(JsonMapper.builder().build().readTree(exchange.getRequestBody().readAllBytes()));
            byte[] reply="{\"request_id\":\"provider-1\",\"output\":{\"choices\":[{\"message\":{\"content\":[{\"image\":\"https://example.test/output.png\"}]}}]}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,reply.length);exchange.getResponseBody().write(reply);exchange.close();
        });server.start();
        try {
            var settings=new ZineProperties();settings.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());settings.setEndpoint("/generate");
            var result=new DashScopeImageGenerationClient(settings).generate(source,"a red panda","test-key").block(Duration.ofSeconds(10));
            assertThat(result.imageUrl()).isEqualTo("https://example.test/output.png");
            assertThat(payload.get().path("model").asText()).isEqualTo(settings.getModel());
            JsonNode content=payload.get().at("/input/messages/0/content");
            assertThat(content.size()).isEqualTo(image?2:1);
            assertThat(content.get(content.size()-1).path("text").asText()).isEqualTo("a red panda");
            if(image)assertThat(content.get(0).path("image").asText()).isEqualTo(source);
            else assertThat(content.get(0).has("image")).isFalse();
        } finally {server.stop(0);}
    }
}
