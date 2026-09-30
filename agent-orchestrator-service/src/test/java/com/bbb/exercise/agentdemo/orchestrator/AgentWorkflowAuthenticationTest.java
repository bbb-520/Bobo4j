package com.bbb.exercise.agentdemo.orchestrator;

import com.bbb.exercise.agentdemo.orchestrator.service.AgentWorkflowService;
import com.bbb.exercise.agentdemo.orchestrator.auth.AuthSessionClient;
import com.bbb.exercise.agentdemo.orchestrator.web.AgentWorkflowController;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

class AgentWorkflowAuthenticationTest {
    private HttpServer auth;

    @AfterEach
    void stopAuth() {
        if (auth != null) auth.stop(0);
    }

    private WebTestClient client(AgentWorkflowService workflows, int authStatus, String body,
                                 AtomicReference<String> forwardedCookie) throws Exception {
        auth = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        auth.createContext("/api/auth/me", request -> {
            forwardedCookie.set(request.getRequestHeaders().getFirst("Cookie"));
            assertThat(request.getRequestHeaders().getFirst("X-User-Id")).isNull();
            request.getResponseHeaders().set("Content-Type", "application/json");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            request.sendResponseHeaders(authStatus, bytes.length);
            try (var output = request.getResponseBody()) { output.write(bytes); }
        });
        auth.start();
        return WebTestClient.bindToController(new AgentWorkflowController(workflows,
                new AuthSessionClient("http://127.0.0.1:" + auth.getAddress().getPort(), "bbb_agent_session"))).build();
    }

    @Test
    void forgedUserHeaderCannotCreateRunWithoutSession() {
        var client = WebTestClient.bindToController(new AgentWorkflowController(new AgentWorkflowService(),
                new AuthSessionClient("http://127.0.0.1:1", "bbb_agent_session"))).build();
        client.post().uri("/api/agent-runs?input=hello").header("X-User-Id", "victim")
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void authenticatedUserCannotReadAnotherOwnersRunEvenWithForgedHeader() throws Exception {
        var workflows = new AgentWorkflowService();
        var victimRun = workflows.start("REVISE", "private", "victim");
        var cookie = new AtomicReference<String>();
        var client = client(workflows, 200,
                "{\"authenticated\":true,\"userId\":\"attacker\",\"username\":\"tester\"}", cookie);
        client.get().uri("/api/agent-runs/" + victimRun.id()).header("X-User-Id", "victim")
                .cookie("bbb_agent_session", "real-session").cookie("unrelated", "private")
                .exchange().expectStatus().isNotFound();
        assertThat(cookie.get()).isEqualTo("bbb_agent_session=real-session");
        client.post().uri("/api/agent-runs/" + victimRun.id() + "/advance?status=REVIEWING")
                .header("X-User-Id", "victim").cookie("bbb_agent_session", "real-session")
                .exchange().expectStatus().isNotFound();
        assertThat(workflows.get(victimRun.id()).status().name()).isEqualTo("DRAFT");
    }

    @Test
    void validSessionCreatesRunForAuthIdentityNotHeader() throws Exception {
        var client = client(new AgentWorkflowService(), 200,
                "{\"authenticated\":true,\"userId\":\"owner\",\"username\":\"tester\"}", new AtomicReference<>());
        client.post().uri("/api/agent-runs?input=hello").header("X-User-Id", "victim")
                .cookie("bbb_agent_session", "real-session").exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.userId").isEqualTo("owner");
    }

    @Test
    void rejectedSessionFailsClosed() throws Exception {
        var client = client(new AgentWorkflowService(), 401, "{}", new AtomicReference<>());
        client.post().uri("/api/agent-runs?input=hello").cookie("bbb_agent_session", "expired")
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void authOutageFailsClosed() throws Exception {
        var client = client(new AgentWorkflowService(), 500, "{}", new AtomicReference<>());
        client.post().uri("/api/agent-runs?input=hello").cookie("bbb_agent_session", "real-session")
                .exchange().expectStatus().isEqualTo(503);
    }
    @Test
    void unauthenticatedResponseIsNotAcceptedAsIdentity() throws Exception {
        var client = client(new AgentWorkflowService(), 200,
                "{\"authenticated\":false,\"userId\":\"victim\"}", new AtomicReference<>());
        client.post().uri("/api/agent-runs?input=hello").cookie("bbb_agent_session", "invalid")
                .exchange().expectStatus().isUnauthorized();
    }
}
