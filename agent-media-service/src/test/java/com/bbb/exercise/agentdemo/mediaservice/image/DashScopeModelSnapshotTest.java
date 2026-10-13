package com.bbb.exercise.agentdemo.mediaservice.image;
import com.bbb.exercise.agentdemo.mediaservice.config.ZineProperties;
import com.bbb.exercise.agentdemo.mediaservice.provider.DashScopeImageGenerationClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
class DashScopeModelSnapshotTest {
    @Test void paidTaskUsesReservedModelRatherThanMutableGlobalDefault() throws Exception {
        var model=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/generate",e->{model.set(JsonMapper.builder().build().readTree(e.getRequestBody().readAllBytes()).path("model").asText());byte[] reply="{\"request_id\":\"one\",\"output\":{\"results\":[{\"url\":\"https://example.test/output.png\"}]}}".getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,reply.length);e.getResponseBody().write(reply);e.close();});server.start();
        try {
            var properties=new ZineProperties();properties.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());properties.setEndpoint("/generate");properties.setModel("global-changed-model");
            var provider=new DashScopeImageGenerationProvider(new DashScopeImageGenerationClient(properties));
            provider.generate(new ImageGenerationProvider.Request(null,"cat","key","reserved-model")).block(Duration.ofSeconds(10));
            assertThat(model.get()).isEqualTo("reserved-model");
        } finally {server.stop(0);}
    }
}
