package com.bbb.exercise.agentdemo.authservice;

import com.bbb.exercise.agentdemo.common.security.SignedPrincipal;
import com.bbb.exercise.agentdemo1_0.auth.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import java.time.Instant;
import java.util.Base64;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InternalAuthBoundaryTest {
    @Test
    void rejectsUnscopedPrincipalBeforeLookingUpUser() {
        byte[] key = new byte[32];
        AuthService auth = mock(AuthService.class);
        when(auth.resolvePublicUser("victim", "local"))
                .thenReturn(new AuthService.UserIdentity("victim", "local", true, "victim"));
        var client = WebTestClient.bindToController(new InternalAuthController(auth,
                "current=" + Base64.getEncoder().encodeToString(key))).build();
        String token = SignedPrincipal.issueScoped("current", "agent-chat-service", "attacker", "local",
                "agent-chat-service", "GET /internal/auth/users/victim", Instant.now(), key);
        client.get().uri("/internal/auth/users/victim").header("X-Internal-Principal", token)
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void rejectsPrincipalWhoseSubjectDoesNotMatchRequestedUser() {
        byte[] key = new byte[32];
        AuthService auth = mock(AuthService.class);
        var client = WebTestClient.bindToController(new InternalAuthController(auth,
                "current=" + Base64.getEncoder().encodeToString(key))).build();
        String token = SignedPrincipal.issueScoped("current", "agent-chat-service", "other", "local",
                "agent-auth-service", "GET /internal/auth/users/victim", Instant.now(), key);
        client.get().uri("/internal/auth/users/victim").header("X-Internal-Principal", token)
                .exchange().expectStatus().isForbidden();
    }
}
