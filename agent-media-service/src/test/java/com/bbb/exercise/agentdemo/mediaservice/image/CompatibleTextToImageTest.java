package com.bbb.exercise.agentdemo.mediaservice.image;

import com.bbb.exercise.agentdemo.api.model.ModelProvider;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;

class CompatibleTextToImageTest {
    @Test void textOnlyUsesGenerationsEndpointWithoutNullImage() throws Exception {
        var path=new AtomicReference<String>();var body=new AtomicReference<JsonNode>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            path.set(exchange.getRequestURI().getPath());body.set(JsonMapper.builder().build().readTree(exchange.getRequestBody().readAllBytes()));
            byte[] reply="{\"id\":\"provider-1\",\"data\":[{\"url\":\"https://example.test/output.png\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,reply.length);exchange.getResponseBody().write(reply);exchange.close();
        });server.start();
        try {
            var provider=new OpenAiCompatibleImageGenerationProvider(ModelProvider.GPT,"http://127.0.0.1:"+server.getAddress().getPort());
            var result=provider.generate(new ImageGenerationProvider.Request(null,"a red panda","test-key","image-model")).block(Duration.ofSeconds(10));
            assertThat(path.get()).isEqualTo("/images/generations");assertThat(body.get().has("image_url")).isFalse();
            assertThat(body.get().path("prompt").asText()).isEqualTo("a red panda");assertThat(result.imageUrl()).isEqualTo("https://example.test/output.png");
        }finally{server.stop(0);}
    }
}
