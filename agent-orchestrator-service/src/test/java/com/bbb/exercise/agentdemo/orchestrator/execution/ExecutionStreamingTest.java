package com.bbb.exercise.agentdemo.orchestrator.execution;

import com.bbb.exercise.agentdemo.api.identity.ChatIdentity;
import com.bbb.exercise.agentdemo.common.security.PrincipalKeyRing;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

class ExecutionStreamingTest {
    @Test void forwardsDeltasAndReplacementBeforeDurableReceiptAndSignsTheStreamRoute() throws Exception {
        var keys=new PrincipalKeyRing("current="+Base64.getEncoder().encodeToString(new byte[32]),"current");
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var principal=new java.util.concurrent.atomic.AtomicReference<String>();
        server.createContext("/internal/chat/stream",exchange->{
            principal.set(exchange.getRequestHeaders().getFirst("X-Internal-Principal"));
            exchange.getRequestBody().readAllBytes();
            String frames="""
                data: {"type":"answer_delta","generationVersion":1,"payload":{"text":"primary"}}

                data: {"type":"fallback_started","generationVersion":2,"payload":{"message":"switching"}}

                data: {"type":"answer_replace","generationVersion":2,"payload":{"text":"backup"}}

                data: {"type":"answer_committed","generationVersion":2,"payload":{"text":"backup","inputTokens":12,"outputTokens":3,"fallback":true}}

                """;
            byte[] bytes=frames.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,bytes.length);
            try(var output=exchange.getResponseBody()){output.write(bytes);}
        });server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            var client=new ExecutionClient(WebClient.builder(),keys,JsonMapper.builder().build(),base,base,base,base);
            var frames=new ArrayList<Map<String,Object>>();
            var receipt=client.stream(new ChatIdentity("tenant","user",true),Map.of("callId","call"),frames::add).get(10,TimeUnit.SECONDS);
            assertThat(frames).extracting(f->f.get("type")).containsExactly("answer_delta","fallback_started","answer_replace");
            assertThat(receipt).containsEntry("text","backup").containsEntry("inputTokens",12);
            assertThat(keys.verify(principal.get(),"agent-chat-service","POST /internal/chat/stream").subject()).isEqualTo("user");
        } finally {server.stop(0);}
    }
    @Test void aStreamEndingWithoutAReceiptCannotCommitAPartialAnswer() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/internal/chat/stream",exchange->{exchange.getRequestBody().readAllBytes();
            byte[] bytes="data: {\"type\":\"answer_delta\",\"generationVersion\":1,\"payload\":{\"text\":\"partial\"}}\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,bytes.length);
            try(var output=exchange.getResponseBody()){output.write(bytes);}});server.start();
        try {String base="http://127.0.0.1:"+server.getAddress().getPort();var client=new ExecutionClient(WebClient.builder(),new PrincipalKeyRing("current="+Base64.getEncoder().encodeToString(new byte[32]),"current"),JsonMapper.builder().build(),base,base,base,base);
            assertThatThrownBy(()->client.stream(new ChatIdentity("t","u",true),Map.of("callId","call"),e->{}).get(10,TimeUnit.SECONDS)).hasCauseInstanceOf(NoSuchElementException.class);
        } finally {server.stop(0);}
    }
}
